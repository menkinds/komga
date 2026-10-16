#!/usr/bin/env bash
# Merge the latest gotson/komga release into this fork and open a pull request.
# Text conflicts are committed with conflict markers so a reviewer can resolve them.
set -euo pipefail

UPSTREAM_REPO="${UPSTREAM_REPO:-gotson/komga}"
DEFAULT_BRANCH="${DEFAULT_BRANCH:-master}"
DRY_RUN="${DRY_RUN:-0}"

if [[ -z "${GH_TOKEN:-}" && "${DRY_RUN}" != "1" ]]; then
  echo "GH_TOKEN is required."
  exit 1
fi

auth_args=()
if [[ -n "${GH_TOKEN:-}" ]]; then
  auth_args=(-H "Authorization: Bearer ${GH_TOKEN}")
fi

if ! git remote get-url upstream >/dev/null 2>&1; then
  git remote add upstream "https://github.com/${UPSTREAM_REPO}.git"
fi
git fetch upstream --tags --force

tag="$(
  curl -fsSL \
    -H "Accept: application/vnd.github+json" \
    "${auth_args[@]}" \
    "https://api.github.com/repos/${UPSTREAM_REPO}/releases/latest" \
    | jq -r '.tag_name'
)"

if [[ -z "${tag}" || "${tag}" == "null" ]]; then
  echo "No upstream release found."
  exit 0
fi

git fetch upstream "refs/tags/${tag}:refs/tags/${tag}" --force

if git merge-base --is-ancestor "${tag}" HEAD; then
  echo "Upstream release ${tag} is already in this branch."
  exit 0
fi

safe_tag="${tag//\//-}"
branch="sync/upstream-${safe_tag}"

if [[ "${DRY_RUN}" != "1" ]]; then
  open_count="$(gh pr list --repo "${GITHUB_REPOSITORY}" --head "${branch}" --base "${DEFAULT_BRANCH}" --state open --json number --jq 'length')"
  if [[ "${open_count}" != "0" ]]; then
    echo "An open pull request already exists for ${branch}."
    exit 0
  fi
fi

git checkout -B "${branch}"

set +e
git merge --no-ff -m "merge upstream ${tag}" "${tag}"
merge_status=$?
set -e

conflicts=""
binaries=""
if [[ "${merge_status}" -eq 1 ]]; then
  unmerged="$(git diff --name-only --diff-filter=U)"
  while IFS= read -r file; do
    [[ -z "${file}" ]] && continue
    # Binary conflicts cannot keep conflict markers. Keep the fork copy and list the file.
    if git diff --numstat -- "${file}" | awk -F '\t' '$1 == "-" { found = 1 } END { exit found ? 0 : 1 }'; then
      git checkout --ours -- "${file}"
      git add -- "${file}"
      binaries="${binaries}${file}"$'\n'
      continue
    fi
    git add -- "${file}"
    conflicts="${conflicts}${file}"$'\n'
  done <<< "${unmerged}"
  if [[ -n "$(git diff --name-only --diff-filter=U)" ]]; then
    echo "Unmerged files remain:"
    git diff --name-only --diff-filter=U
    exit 1
  fi
  git add -A
  git commit -m "merge upstream ${tag}" -m "Conflict markers are left in place for review."
elif [[ "${merge_status}" -ne 0 ]]; then
  echo "git merge failed with status ${merge_status}"
  exit "${merge_status}"
fi

if [[ "${DRY_RUN}" == "1" ]]; then
  echo "Dry run. Branch ${branch} was created locally and was not pushed."
  exit 0
fi

if git ls-remote --exit-code --heads origin "${branch}" >/dev/null; then
  git push origin --delete "${branch}"
fi
git push -u origin "${branch}"

body="gotson/komga 릴리스 \`${tag}\` 를 이 포크의 \`${DEFAULT_BRANCH}\` 에 머지하는 풀 리퀘스트입니다."
if [[ -n "${conflicts}" ]]; then
  body="${body}

충돌 표시를 파일에 그대로 두었습니다. 머지 전에 \`<<<<<<<\` 가 있는 파일을 고치고 표시를 지워 주세요.

\`\`\`
${conflicts}
\`\`\`"
fi
if [[ -n "${binaries}" ]]; then
  body="${body}

바이너리 파일은 충돌 표시를 저장할 수 없어 포크 쪽 내용을 남겨 두었습니다. 어느 쪽을 쓸지 확인해 주세요.

\`\`\`
${binaries}
\`\`\`"
fi

gh pr create \
  --repo "${GITHUB_REPOSITORY}" \
  --base "${DEFAULT_BRANCH}" \
  --head "${branch}" \
  --title "merge upstream ${tag}" \
  --body "${body}"
