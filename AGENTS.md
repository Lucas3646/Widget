# Repository instructions

## Deployment policy — Vercel is manual-only

**Do not deploy to Vercel unless the user explicitly requests deployment in their current prompt.**

- A commit, pull request, branch push, merge into `main`, tag, build, or task completion is **not** authorization to deploy.
- Never call the Vercel deploy, redeploy, promote, or production release APIs/tools; do not run `vercel deploy`, `vercel --prod`, a deploy hook, or a GitHub Actions release unless the user expressly asks for it.
- Keep automatic Vercel Git deployments disabled via `git.deploymentEnabled: false` in the existing project `vercel.json` (for EFB Pocket/LAPL this file is `web/vercel.json`). Never remove, override, or change this safeguard without the user's explicit permission.
- For repositories not currently connected to Vercel, keep any future Vercel integration manual-only as well. Do not add automatic deploy workflows.
- When the user requests a deployment, deploy exactly the requested project, commit/branch, and environment; verify the result, and leave automatic deployments disabled afterwards.
- GitHub source changes and CI tests are allowed without deploying Vercel, subject to the rest of this repository's rules.
