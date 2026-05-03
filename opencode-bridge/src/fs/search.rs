use serde::{Deserialize, Serialize};
use std::path::Path;

#[derive(Debug, Deserialize)]
pub struct SearchRequest {
    pub path: String,
    pub query: String,
    #[serde(default = "default_search_type")]
    pub search_type: String,
    #[serde(default = "default_max_results")]
    pub max_results: usize,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchResult {
    pub path: String,
    pub line: Option<usize>,
    pub column: Option<usize>,
    pub snippet: Option<String>,
}

fn default_search_type() -> String {
    "filename".to_string()
}

fn default_max_results() -> usize {
    50
}

pub fn search_files(request: &SearchRequest) -> Result<Vec<SearchResult>, String> {
    let root = Path::new(&request.path);
    if !root.exists() {
        return Err(format!("Path does not exist: {}", request.path));
    }

    match request.search_type.as_str() {
        "filename" => search_by_filename(root, &request.query, request.max_results),
        "content" => search_by_content(root, &request.query, request.max_results),
        _ => Err(format!("Unknown search type: {}", request.search_type)),
    }
}

fn search_by_filename(
    root: &Path,
    query: &str,
    max_results: usize,
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
        if name.to_lowercase().contains(&query_lower) {
            results.push(SearchResult {
                path: path.to_string_lossy().to_string(),
                line: None,
                column: None,
                snippet: None,
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
) -> Result<Vec<SearchResult>, String> {
    let mut results = Vec::new();

    walk_dir(root, &mut |path| {
        if results.len() >= max_results {
            return false;
        }

        if !path.is_file() {
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
                results.push(SearchResult {
                    path: path.to_string_lossy().to_string(),
                    line: Some(i + 1),
                    column: Some(line.find(query).unwrap_or(0) + 1),
                    snippet: Some(snippet),
                });
            }
        }

        results.len() < max_results
    })?;

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
        };
        let results = search_files(&req).unwrap();
        assert_eq!(results.len(), 1);
        assert!(results[0].path.contains("src"));

        let _ = fs::remove_dir_all(&dir);
    }
}
