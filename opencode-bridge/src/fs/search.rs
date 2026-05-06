use serde::{Deserialize, Serialize};
use std::path::Path;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchRequest {
    pub path: String,
    pub query: String,
    #[serde(default = "default_search_type")]
    pub search_type: String,
    #[serde(default = "default_max_results")]
    pub max_results: usize,
    #[serde(default)]
    pub glob: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchResult {
    pub path: String,
    pub line: Option<usize>,
    pub column: Option<usize>,
    pub snippet: Option<String>,
    #[serde(default)]
    pub is_directory: bool,
    #[serde(default)]
    pub size: u64,
    #[serde(default)]
    pub modified: u64,
}

fn default_search_type() -> String {
    "filename".to_string()
}

fn default_max_results() -> usize {
    50
}

fn file_meta(path: &Path) -> (bool, u64, u64) {
    match std::fs::metadata(path) {
        Ok(m) => {
            let is_dir = m.is_dir();
            let size = if is_dir { 0 } else { m.len() };
            let modified = m.modified()
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0);
            (is_dir, size, modified)
        }
        Err(_) => (false, 0, 0),
    }
}

pub fn search_files(request: &SearchRequest) -> Result<Vec<SearchResult>, String> {
    let glob_pattern = request.glob.as_deref().filter(|g| !g.is_empty());
    match request.search_type.as_str() {
        "filename" => {
            let root = Path::new(&request.path);
            if !root.exists() {
                return Err(format!("Path does not exist: {}", request.path));
            }
            search_by_filename(root, &request.query, request.max_results, glob_pattern)
        }
        "content" => {
            let root = Path::new(&request.path);
            if !root.exists() {
                return Err(format!("Path does not exist: {}", request.path));
            }
            search_by_content(root, &request.query, request.max_results, glob_pattern)
        }
        "prefix" => search_by_prefix(&request.query),
        _ => Err(format!("Unknown search type: {}", request.search_type)),
    }
}

fn glob_matches(pattern: &str, name: &str) -> bool {
    let p = pattern.to_lowercase();
    let n = name.to_lowercase();
    let pi: Vec<char> = p.chars().collect();
    let ni: Vec<char> = n.chars().collect();
    fn match_at(pi: &[char], ni: &[char]) -> bool {
        if pi.is_empty() {
            return ni.is_empty();
        }
        if pi[0] == '*' {
            if pi.len() == 1 {
                return true;
            }
            for i in 0..=ni.len() {
                if match_at(&pi[1..], &ni[i..]) {
                    return true;
                }
            }
            return false;
        }
        if ni.is_empty() {
            return false;
        }
        if pi[0] == '?' || pi[0] == ni[0] {
            return match_at(&pi[1..], &ni[1..]);
        }
        false
    }
    match_at(&pi, &ni)
}

fn should_skip_file(name: &str, glob_pattern: Option<&str>) -> bool {
    if let Some(pattern) = glob_pattern {
        !glob_matches(pattern, name)
    } else {
        false
    }
}

fn search_by_filename(
    root: &Path,
    query: &str,
    max_results: usize,
    glob_pattern: Option<&str>,
) -> Result<Vec<SearchResult>, String> {
    let mut results = Vec::new();
    let query_lower = query.to_lowercase();

    walk_dir(root, &mut |path| {
        if results.len() >= max_results {
            return false;
        }
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default();
        if should_skip_file(&name, glob_pattern) {
            return true;
        }
        if name.to_lowercase().contains(&query_lower) {
            let (is_dir, size, modified) = file_meta(path);
            results.push(SearchResult {
                path: path.to_string_lossy().to_string(),
                line: None,
                column: None,
                snippet: None,
                is_directory: is_dir,
                size,
                modified,
            });
        }
        results.len() < max_results
    })?;

    Ok(results)
}

fn search_by_content(
    root: &Path,
    query: &str,
    max_results: usize,
    glob_pattern: Option<&str>,
) -> Result<Vec<SearchResult>, String> {
    let mut results = Vec::new();

    walk_dir(root, &mut |path| {
        if results.len() >= max_results {
            return false;
        }

        if !path.is_file() {
            return true;
        }

        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default();
        if should_skip_file(&name, glob_pattern) {
            return true;
        }

        let content = match std::fs::read_to_string(path) {
            Ok(c) => c,
            Err(_) => return true,
        };

        for (i, line) in content.lines().enumerate() {
            if results.len() >= max_results {
                return false;
            }
            if line.contains(query) {
                let snippet = if line.len() > 200 {
                    format!("{}...", &line[..200])
                } else {
                    line.to_string()
                };
                let (_, size, modified) = file_meta(path);
                results.push(SearchResult {
                    path: path.to_string_lossy().to_string(),
                    line: Some(i + 1),
                    column: Some(line.find(query).unwrap_or(0) + 1),
                    snippet: Some(snippet),
                    is_directory: false,
                    size,
                    modified,
                });
            }
        }

        results.len() < max_results
    })?;

    Ok(results)
}

fn search_by_prefix(query: &str) -> Result<Vec<SearchResult>, String> {
    let normalized = query.replace('\\', "/");
    let normalized = if normalized.starts_with('/') || normalized.len() >= 2 && normalized.as_bytes()[1] == b':' {
        normalized
    } else {
        return Ok(Vec::new());
    };

    let (parent, prefix) = if normalized.ends_with('/') {
        (normalized.clone(), String::new())
    } else {
        let idx = normalized.rfind('/');
        match idx {
            Some(i) => (normalized[..=i].to_string(), normalized[i + 1..].to_string()),
            None => (normalized.clone(), String::new()),
        }
    };

    let parent_path = Path::new(&parent);
    if !parent_path.exists() || !parent_path.is_dir() {
        return Ok(Vec::new());
    }

    let case_sensitive = !cfg!(target_os = "windows");
    let prefix_for_cmp = if case_sensitive { prefix.clone() } else { prefix.to_lowercase() };

    let mut results = Vec::new();
    let entries = std::fs::read_dir(parent_path).map_err(|e| e.to_string())?;

    for entry in entries {
        let entry = match entry {
            Ok(e) => e,
            Err(_) => continue,
        };
        if !entry.file_type().map(|t| t.is_dir()).unwrap_or(false) {
            continue;
        }
        let name = entry.file_name().to_string_lossy().to_string();
        let name_for_cmp = if case_sensitive { name.clone() } else { name.to_lowercase() };
        if prefix_for_cmp.is_empty() || name_for_cmp.starts_with(&prefix_for_cmp) {
            let full = format!("{}/{}", parent.trim_end_matches('/'), name);
            let (_, _, modified) = file_meta(Path::new(&full));
            results.push(SearchResult {
                path: full,
                line: None,
                column: None,
                snippet: None,
                is_directory: true,
                size: 0,
                modified,
            });
        }
    }

    if !prefix_for_cmp.is_empty() {
        let full_prefix_path = format!("{}/{}", parent.trim_end_matches('/'), prefix);
        let fp = Path::new(&full_prefix_path);
        if fp.exists() && fp.is_dir() {
            if let Ok(entries) = std::fs::read_dir(fp) {
                for entry in entries.flatten() {
                    if entry.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                        let child_name = entry.file_name().to_string_lossy().to_string();
                        let child_path = format!("{}/{}", full_prefix_path.trim_end_matches('/'), child_name);
                        let (_, _, modified) = file_meta(Path::new(&child_path));
                        results.push(SearchResult {
                            path: child_path,
                            line: None,
                            column: None,
                            snippet: None,
                            is_directory: true,
                            size: 0,
                            modified,
                        });
                    }
                }
            }
        }
    }

    Ok(results)
}

fn walk_dir<F>(dir: &Path, callback: &mut F) -> Result<(), String>
where
    F: FnMut(&Path) -> bool,
{
    let entries = std::fs::read_dir(dir).map_err(|e| e.to_string())?;

    for entry in entries {
        let entry = entry.map_err(|e| e.to_string())?;
        let path = entry.path();

        if !callback(&path) {
            return Ok(());
        }

        if path.is_dir() {
            let name = path
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default();
            if name.starts_with('.') || name == "node_modules" || name == "target" {
                continue;
            }
            walk_dir(&path, callback)?;
        }
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    static TEST_COUNTER: AtomicU64 = AtomicU64::new(0);

    fn unique_test_dir(prefix: &str) -> PathBuf {
        let id = TEST_COUNTER.fetch_add(1, Ordering::Relaxed);
        let dir = std::env::temp_dir().join(format!("bridge_search_{}_{}", prefix, id));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn test_search_by_filename() {
        let dir = unique_test_dir("fname");
        fs::write(dir.join("hello.rs"), "fn main() {}").unwrap();
        fs::write(dir.join("world.ts"), "console.log()").unwrap();
        fs::write(dir.join("readme.md"), "# Hello").unwrap();

        let req = SearchRequest {
            path: dir.to_str().unwrap().to_string(),
            query: "rs".to_string(),
            search_type: "filename".to_string(),
            max_results: 50,
            glob: None,
        };
        let results = search_files(&req).unwrap();
        assert_eq!(results.len(), 1);
        assert!(results[0].path.ends_with("hello.rs"));
        assert!(results[0].line.is_none());

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_search_by_filename_case_insensitive() {
        let dir = unique_test_dir("ci");
        fs::write(dir.join("README.md"), "").unwrap();

        let req = SearchRequest {
            path: dir.to_str().unwrap().to_string(),
            query: "readme".to_string(),
            search_type: "filename".to_string(),
            max_results: 50,
            glob: None,
        };
        let results = search_files(&req).unwrap();
        assert_eq!(results.len(), 1);

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_search_by_content() {
        let dir = unique_test_dir("content");
        fs::write(dir.join("app.rs"), "fn main() {\n    TODO: fix this\n}").unwrap();
        fs::write(dir.join("lib.rs"), "pub fn helper() {}").unwrap();

        let req = SearchRequest {
            path: dir.to_str().unwrap().to_string(),
            query: "TODO".to_string(),
            search_type: "content".to_string(),
            max_results: 50,
            glob: None,
        };
        let results = search_files(&req).unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].line, Some(2));
        assert!(results[0].snippet.is_some());

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_search_content_column() {
        let dir = unique_test_dir("col");
        fs::write(dir.join("code.txt"), "abcXYZdef").unwrap();

        let req = SearchRequest {
            path: dir.to_str().unwrap().to_string(),
            query: "XYZ".to_string(),
            search_type: "content".to_string(),
            max_results: 50,
            glob: None,
        };
        let results = search_files(&req).unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].column, Some(4));

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_search_max_results() {
        let dir = unique_test_dir("max");
        for i in 0..10 {
            fs::write(dir.join(format!("file_{}.txt", i)), "match").unwrap();
        }

        let req = SearchRequest {
            path: dir.to_str().unwrap().to_string(),
            query: "file_".to_string(),
            search_type: "filename".to_string(),
            max_results: 3,
            glob: None,
        };
        let results = search_files(&req).unwrap();
        assert_eq!(results.len(), 3);

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_search_skips_hidden_and_node_modules() {
        let dir = unique_test_dir("skip");
        fs::create_dir(dir.join(".hidden")).unwrap();
        fs::write(dir.join(".hidden").join("secret.txt"), "findme").unwrap();
        fs::create_dir(dir.join("node_modules")).unwrap();
        fs::write(dir.join("node_modules").join("pkg.txt"), "findme").unwrap();
        fs::write(dir.join("visible.txt"), "findme").unwrap();

        let req = SearchRequest {
            path: dir.to_str().unwrap().to_string(),
            query: "findme".to_string(),
            search_type: "content".to_string(),
            max_results: 50,
            glob: None,
        };
        let results = search_files(&req).unwrap();
        assert_eq!(results.len(), 1);
        assert!(results[0].path.ends_with("visible.txt"));

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_search_nonexistent_path() {
        let req = SearchRequest {
            path: "/nonexistent_bridge_search_path".to_string(),
            query: "test".to_string(),
            search_type: "filename".to_string(),
            max_results: 50,
            glob: None,
        };
        assert!(search_files(&req).is_err());
    }

    #[test]
    fn test_search_unknown_type() {
        let dir = unique_test_dir("unk");
        let req = SearchRequest {
            path: dir.to_str().unwrap().to_string(),
            query: "test".to_string(),
            search_type: "regex".to_string(),
            max_results: 50,
            glob: None,
        };
        assert!(search_files(&req).is_err());

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_search_content_multiple_matches_in_file() {
        let dir = unique_test_dir("multi");
        fs::write(dir.join("multi.txt"), "TODO: a\nnot this\nTODO: b").unwrap();

        let req = SearchRequest {
            path: dir.to_str().unwrap().to_string(),
            query: "TODO".to_string(),
            search_type: "content".to_string(),
            max_results: 50,
            glob: None,
        };
        let results = search_files(&req).unwrap();
        assert_eq!(results.len(), 2);
        assert_eq!(results[0].line, Some(1));
        assert_eq!(results[1].line, Some(3));

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_search_subdirectories() {
        let dir = unique_test_dir("sub");
        let sub = dir.join("src");
        fs::create_dir_all(&sub).unwrap();
        fs::write(sub.join("main.rs"), "fn main() {}").unwrap();

        let req = SearchRequest {
            path: dir.to_str().unwrap().to_string(),
            query: "main.rs".to_string(),
            search_type: "filename".to_string(),
            max_results: 50,
            glob: None,
        };
        let results = search_files(&req).unwrap();
        assert_eq!(results.len(), 1);
        assert!(results[0].path.contains("src"));

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_glob_filters_filename_search() {
        let dir = unique_test_dir("glob_fn");
        fs::write(dir.join("app.rs"), "fn main()").unwrap();
        fs::write(dir.join("app.ts"), "function main()").unwrap();
        fs::write(dir.join("readme.md"), "# app").unwrap();

        let req = SearchRequest {
            path: dir.to_str().unwrap().to_string(),
            query: "app".to_string(),
            search_type: "filename".to_string(),
            max_results: 50,
            glob: Some("*.rs".to_string()),
        };
        let results = search_files(&req).unwrap();
        assert_eq!(results.len(), 1);
        assert!(results[0].path.ends_with("app.rs"));

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_glob_filters_content_search() {
        let dir = unique_test_dir("glob_content");
        fs::write(dir.join("app.rs"), "fn find() {}").unwrap();
        fs::write(dir.join("app.ts"), "function find() {}").unwrap();

        let req = SearchRequest {
            path: dir.to_str().unwrap().to_string(),
            query: "find".to_string(),
            search_type: "content".to_string(),
            max_results: 50,
            glob: Some("*.rs".to_string()),
        };
        let results = search_files(&req).unwrap();
        assert_eq!(results.len(), 1);
        assert!(results[0].path.ends_with("app.rs"));

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_glob_wildcard_question_mark() {
        let dir = unique_test_dir("glob_qmark");
        fs::write(dir.join("a1.txt"), "x").unwrap();
        fs::write(dir.join("a2.txt"), "x").unwrap();
        fs::write(dir.join("ab12.txt"), "x").unwrap();

        let req = SearchRequest {
            path: dir.to_str().unwrap().to_string(),
            query: "a".to_string(),
            search_type: "filename".to_string(),
            max_results: 50,
            glob: Some("a?.txt".to_string()),
        };
        let results = search_files(&req).unwrap();
        assert_eq!(results.len(), 2);

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_glob_case_insensitive() {
        let dir = unique_test_dir("glob_case");
        fs::write(dir.join("App.RS"), "fn main()").unwrap();

        let req = SearchRequest {
            path: dir.to_str().unwrap().to_string(),
            query: "app".to_string(),
            search_type: "filename".to_string(),
            max_results: 50,
            glob: Some("*.rs".to_string()),
        };
        let results = search_files(&req).unwrap();
        assert_eq!(results.len(), 1);

        let _ = fs::remove_dir_all(&dir);
    }
}
