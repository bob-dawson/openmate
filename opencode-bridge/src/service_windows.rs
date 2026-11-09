use std::ffi::OsString;
use std::sync::mpsc;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Notify;
use windows_service::define_windows_service;
use windows_service::service::{
    ServiceAccess, ServiceAction, ServiceActionType, ServiceControl, ServiceControlAccept,
    ServiceErrorControl, ServiceExitCode, ServiceFailureActions, ServiceFailureResetPeriod,
    ServiceInfo, ServiceState, ServiceStatus, ServiceType,
};
use windows_service::service_control_handler::{self, ServiceControlHandlerResult};
use windows_service::service_manager::{ServiceManager, ServiceManagerAccess};
use windows_service::service_dispatcher;

use crate::bridge_db::BridgeDb;
use crate::config::Config;
use crate::log_capture::{LogCaptureLayer, create_shared_buffer};
use crate::server::run_server;

const SERVICE_NAME: &str = "OpenMate";
const SERVICE_DISPLAY_NAME: &str = "OpenMate Bridge";
const SERVICE_DESCRIPTION: &str = "OpenMate Bridge - proxy, file serving, and authentication for OpenCode";
const RECOVERY_CONFIG_KEY: &str = "service.restart_on_failure";

fn configure_recovery(service: &windows_service::service::Service) -> bool {
    let actions = vec![
        ServiceAction {
            action_type: ServiceActionType::Restart,
            delay: Duration::from_secs(5),
        },
        ServiceAction {
            action_type: ServiceActionType::Restart,
            delay: Duration::from_secs(10),
        },
        ServiceAction {
            action_type: ServiceActionType::Restart,
            delay: Duration::from_secs(30),
        },
    ];
    let configured = service
        .update_failure_actions(ServiceFailureActions {
            reset_period: ServiceFailureResetPeriod::After(Duration::from_secs(86400)),
            reboot_msg: None,
            command: None,
            actions: Some(actions),
        })
        .and_then(|_| service.set_failure_actions_on_non_crash_failures(true));
    match configured {
        Ok(()) => true,
        Err(e) => {
            tracing::warn!("Failed to configure service recovery actions: {}", e);
            false
        }
    }
}

fn try_configure_recovery() -> bool {
    let manager = match ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT) {
        Ok(manager) => manager,
        Err(e) => {
            tracing::warn!("Failed to open service manager for recovery setup: {}", e);
            return false;
        }
    };
    match manager.open_service(SERVICE_NAME, ServiceAccess::CHANGE_CONFIG) {
        Ok(service) => configure_recovery(&service),
        Err(e) => {
            tracing::warn!("Failed to open service for recovery setup: {}", e);
            false
        }
    }
}

pub fn install() -> anyhow::Result<()> {
    let exe_path = std::env::current_exe()?;
    let exe_str = exe_path.to_str().ok_or_else(|| anyhow::anyhow!("Invalid exe path"))?;
    println!("Installing service from: {}", exe_str);

    let manager_access = ServiceManagerAccess::CONNECT | ServiceManagerAccess::CREATE_SERVICE;
    let service_manager = ServiceManager::local_computer(None::<&str>, manager_access).map_err(|e| {
        anyhow::anyhow!("Failed to open service manager: {} (are you running as Administrator?)", e)
    })?;

    let service_info = ServiceInfo {
        name: SERVICE_NAME.into(),
        display_name: SERVICE_DISPLAY_NAME.into(),
        service_type: ServiceType::OWN_PROCESS,
        start_type: windows_service::service::ServiceStartType::AutoStart,
        error_control: ServiceErrorControl::Normal,
        executable_path: exe_path.clone(),
        launch_arguments: vec![OsString::from("service")],
        dependencies: vec![],
        account_name: None,
        account_password: None,
    };

    let service = service_manager.create_service(&service_info, ServiceAccess::CHANGE_CONFIG)?;
    service.set_description(SERVICE_DESCRIPTION)?;

    if configure_recovery(&service) {
        if let Ok(db) = BridgeDb::open() {
            let _ = db.set_config(RECOVERY_CONFIG_KEY, "1");
        }
        println!("Configured automatic restart on failure (required for in-app upgrades).");
    } else {
        println!("WARNING: could not configure automatic restart on failure.");
    }

    println!("{} service installed successfully.", SERVICE_NAME);
    println!("");
    println!("IMPORTANT: This service must NOT run as LocalSystem.");
    println!("Please update the service to run as your user account:");
    println!("  1. Open services.msc");
    println!("  2. Find '{}' service", SERVICE_NAME);
    println!("  3. Right-click → Properties → Log On");
    println!("  4. Select 'This account' and enter your Windows username and password");
    println!("  5. Click OK, then start the service");
    println!("");
    println!("Or run from command line (as Administrator):");
    println!("  sc config {} obj= \\\"{}\\\" password= <your_password>", SERVICE_NAME, std::env::var("USERNAME").unwrap_or_else(|_| "USERNAME".to_string()));
    Ok(())
}

pub fn uninstall() -> anyhow::Result<()> {
    let manager_access = ServiceManagerAccess::CONNECT;
    let service_manager = ServiceManager::local_computer(None::<&str>, manager_access).map_err(|e| {
        anyhow::anyhow!("Failed to open service manager: {} (are you running as Administrator?)", e)
    })?;

    let access = ServiceAccess::QUERY_STATUS | ServiceAccess::STOP | ServiceAccess::DELETE;
    let service = service_manager.open_service(SERVICE_NAME, access).map_err(|e| {
        anyhow::anyhow!("Failed to open service '{}': {} (is it installed?)", SERVICE_NAME, e)
    })?;

    let status = service.query_status()?;
    if status.current_state != ServiceState::Stopped {
        service.stop()?;
        std::thread::sleep(Duration::from_secs(2));
    }

    service.delete()?;
    drop(service);

    println!("{} service uninstalled successfully.", SERVICE_NAME);
    Ok(())
}

define_windows_service!(ffi_service_main, service_main);

fn service_main(_arguments: Vec<OsString>) {
    if let Err(e) = run_service_inner() {
        tracing::error!("Service error: {}", e);
    }
}

fn is_running_as_local_system() -> bool {
    let username = std::env::var("USERNAME").unwrap_or_default();
    username.eq_ignore_ascii_case("SYSTEM")
        || username.eq_ignore_ascii_case("LOCALSYSTEM")
        || username.eq_ignore_ascii_case("$")
}

fn run_service_inner() -> anyhow::Result<()> {
    if is_running_as_local_system() {
        anyhow::bail!(
            "Service is running as LocalSystem, which is not allowed. \
             Please configure the service to run as your user account in services.msc"
        );
    }

    let (shutdown_tx, shutdown_rx) = mpsc::channel::<()>();

    let event_handler = move |control_event| -> ServiceControlHandlerResult {
        match control_event {
            ServiceControl::Stop | ServiceControl::Shutdown => {
                let _ = shutdown_tx.send(());
                ServiceControlHandlerResult::NoError
            }
            ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
            _ => ServiceControlHandlerResult::NotImplemented,
        }
    };

    let status_handle = service_control_handler::register(SERVICE_NAME, event_handler)?;

    status_handle.set_service_status(ServiceStatus {
        service_type: ServiceType::OWN_PROCESS,
        current_state: ServiceState::Running,
        controls_accepted: ServiceControlAccept::STOP | ServiceControlAccept::SHUTDOWN,
        exit_code: ServiceExitCode::Win32(0),
        checkpoint: 0,
        wait_hint: Duration::default(),
        process_id: None,
    })?;

    let bridge_db = BridgeDb::open().map_err(|e| anyhow::anyhow!("{}", e))?;
    bridge_db.init_default_configs().map_err(|e| anyhow::anyhow!("{}", e))?;
    let config = Config::load_from_db(&bridge_db)?;
    config.ensure_opencode_binary()?;

    let log_buffer = create_shared_buffer();
    {
        use tracing_subscriber::layer::SubscriberExt;
        use tracing_subscriber::util::SubscriberInitExt;
        let capture_layer = LogCaptureLayer::new(log_buffer.clone());
        let env_filter = tracing_subscriber::EnvFilter::try_from_default_env()
            .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info"));
        tracing_subscriber::registry()
            .with(env_filter)
            .with(capture_layer)
            .init();
    }

    let mut recovery = bridge_db.get_config(RECOVERY_CONFIG_KEY).ok().flatten().as_deref()
        == Some("1");
    if !recovery {
        recovery = try_configure_recovery();
        if recovery {
            let _ = bridge_db.set_config(RECOVERY_CONFIG_KEY, "1");
        }
    }
    if recovery {
        unsafe { std::env::set_var("OPENMATE_SERVICE_MODE", "1") };
        tracing::info!("Service recovery actions enabled; in-app upgrades restart automatically");
    } else {
        tracing::warn!("Service recovery actions unavailable; in-app upgrade falls back to detached restart");
    }

    let rt = tokio::runtime::Runtime::new()?;
    let notify = Arc::new(Notify::new());
    let notify_clone = notify.clone();

    std::thread::spawn(move || {
        let _ = shutdown_rx.recv();
        notify_clone.notify_one();
    });

    let (shutdown_tx, shutdown_rx) = tokio::sync::watch::channel(false);
    let result = rt.block_on(run_server(log_buffer, Some((shutdown_tx, shutdown_rx)), None, None));

    status_handle.set_service_status(ServiceStatus {
        service_type: ServiceType::OWN_PROCESS,
        current_state: ServiceState::Stopped,
        controls_accepted: ServiceControlAccept::empty(),
        exit_code: ServiceExitCode::Win32(0),
        checkpoint: 0,
        wait_hint: Duration::default(),
        process_id: None,
    })?;

    result
}

pub fn run_service() -> anyhow::Result<()> {
    service_dispatcher::start(SERVICE_NAME, ffi_service_main)?;
    Ok(())
}
