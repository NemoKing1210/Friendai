#!/bin/sh
# One-off: protect `main` so that nothing merges unless CI and commit lint pass.
# Needs the GitHub CLI logged in with admin rights: sh scripts/setup-branch-protection.sh [owner/repo]
# Run it after the workflows have run once, so GitHub knows the check names.
set -e
repo="${1:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"

gh api -X PUT "repos/${repo}/branches/main/protection" --input - <<JSON
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["CI gate", "Commit lint gate"]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": null,
  "restrictions": null,
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true
}
JSON
echo "Branch protection enabled on ${repo}:main"
