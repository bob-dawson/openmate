#!/usr/bin/env bash
# Mirror a GitHub Release (notes + assets) to AtomGit, so users in regions
# with restricted access to GitHub can download the artifacts.
#
# Expects the repository to be checked out (for pushing the tag) and env:
#   GH_TOKEN, ATOMGIT_USER, ATOMGIT_TOKEN, TAG
# Optional env: ATOMGIT_REPO (default "openmate"), ATOMGIT_HOST (default "atomgit.com")
set -euo pipefail

if [ -z "${ATOMGIT_USER:-}" ] || [ -z "${ATOMGIT_TOKEN:-}" ]; then
  echo "AtomGit credentials not configured; skipping release mirror."
  exit 0
fi
: "${TAG:?TAG is required}"
ATOMGIT_REPO="${ATOMGIT_REPO:-openmate}"
ATOMGIT_HOST="${ATOMGIT_HOST:-atomgit.com}"

api="https://${ATOMGIT_HOST}/api/v5/repos/${ATOMGIT_USER}/${ATOMGIT_REPO}"
auth=(-H "PRIVATE-TOKEN: ${ATOMGIT_TOKEN}")
connect_timeout=(--connect-timeout 20)
retry=(--retry 4 --retry-delay 5 --retry-all-errors)

# 1) Best-effort: make sure the tag exists on AtomGit.
git fetch --tags origin >/dev/null 2>&1 || true
git push "https://${ATOMGIT_USER}:${ATOMGIT_TOKEN}@${ATOMGIT_HOST}/${ATOMGIT_USER}/${ATOMGIT_REPO}.git" \
  "refs/tags/${TAG}:refs/tags/${TAG}" >/dev/null 2>&1 || true

# 2) Read the GitHub release notes.
gh release view "$TAG" --json body > /tmp/rel.json
jq -r '.body // ""' /tmp/rel.json > /tmp/body.txt

# 3) Create (or ensure) the release on AtomGit.
payload=$(jq -n --arg t "$TAG" --arg n "$TAG" --rawfile b /tmp/body.txt '{tag_name:$t,name:$n,body:$b,prerelease:false}')
code=$(curl -s "${connect_timeout[@]}" "${retry[@]}" --max-time 120 -o /tmp/create.json -w "%{http_code}" -X POST "${auth[@]}" -H "Content-Type: application/json" \
  "$api/releases?access_token=${ATOMGIT_TOKEN}" -d "$payload")
echo "create release ${TAG} -> HTTP ${code}"
if [ "$code" != "200" ] && [ "$code" != "201" ]; then
  echo "  $(head -c 300 /tmp/create.json)"
fi

# 4) Upload each asset that is not already present (idempotent re-runs).
mkdir -p /tmp/assets
existing=$(curl -s "${connect_timeout[@]}" "${retry[@]}" --max-time 120 "${auth[@]}" "$api/releases/${TAG}" | jq -r '.assets[]?.name' 2>/dev/null || true)
mapfile -t assets < <(gh release view "$TAG" --json assets --jq '.assets[].name')
failures=0
for fname in "${assets[@]}"; do
  # Re-check AtomGit each iteration so a partial earlier run is resumed.
  if printf '%s\n' "$existing" | grep -Fxq "$fname"; then
    echo "skip ${fname} (already on AtomGit)"
    continue
  fi
  gh release download "$TAG" -p "$fname" -D /tmp/assets --clobber
  encoded=$(jq -rn --arg x "$fname" '$x|@uri')
  upload_json=$(curl -s "${connect_timeout[@]}" "${retry[@]}" --max-time 120 "${auth[@]}" "$api/releases/${TAG}/upload_url?access_token=${ATOMGIT_TOKEN}&file_name=${encoded}")
  url=$(echo "$upload_json" | jq -r '.url // empty')
  if [ -z "$url" ]; then
    echo "upload ${fname} -> no upload url: $(echo "$upload_json" | head -c 200)"
    failures=$((failures + 1))
    continue
  fi
  headers=()
  while IFS=$'\t' read -r k v; do headers+=(-H "$k: $v"); done < <(echo "$upload_json" | jq -r '.headers // {} | to_entries[] | "\(.key)\t\(.value)"')
  # Large assets + slow links: keep a generous per-attempt cap, but never hang forever.
  ucode=$(curl -s "${connect_timeout[@]}" "${retry[@]}" --retry-max-time 1800 --max-time 1200 -o /dev/null -w "%{http_code}" -X PUT "${headers[@]}" --data-binary @"/tmp/assets/${fname}" "$url")
  echo "upload ${fname} -> HTTP ${ucode}"
  if [ "${ucode:0:1}" != "2" ]; then
    echo "  !! upload failed for ${fname}"
    failures=$((failures + 1))
  fi
done

# 5) Verify.
echo "== AtomGit release assets =="
final=$(curl -s "${connect_timeout[@]}" "${retry[@]}" --max-time 120 "${auth[@]}" "$api/releases/${TAG}" | jq -r '.assets[]?.name' 2>/dev/null || true)
printf '%s\n' "$final"

if [ "$failures" -gt 0 ]; then
  echo "AtomGit mirror incomplete: ${failures} asset(s) failed to upload."
  exit 1
fi
