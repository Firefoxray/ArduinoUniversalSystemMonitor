"""Conservative Git preview updater; no installer, service changes or resets."""
from __future__ import annotations

from dataclasses import dataclass
import os
import subprocess


class UpdateError(RuntimeError):
    pass


UPSTREAM = "origin/feature/v13-qt-control-center-foundation"


@dataclass(frozen=True)
class UpdatePlan:
    head: str
    target: str
    behind: int
    summary: str


class PreviewUpdater:
    def __init__(self, root):
        self.root = root

    def git(self, *args, timeout=15):
        try:
            result = subprocess.run(
                ["git", *args], cwd=self.root, capture_output=True, text=True,
                encoding="utf-8", errors="replace", timeout=timeout,
                env={**os.environ, "GIT_TERMINAL_PROMPT": "0"},
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise UpdateError(f"Git unavailable or timed out: {exc}") from exc
        if result.returncode:
            raise UpdateError((result.stderr or result.stdout or "Git failed").strip())
        return result.stdout.strip()

    def preflight(self):
        if self.git("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}") != UPSTREAM:
            raise UpdateError("This updater requires the v13 preview tracking branch. Stable main is not updated here.")
        status = self.git("status", "--porcelain", "--untracked-files=normal")
        if status:
            lines = status.splitlines()
            details = "\n".join(lines[:15])
            if len(lines) > 15:
                details += f"\n… and {len(lines) - 15} more"
            raise UpdateError(f"Local changes or untracked files found. Review git status before updating:\n\n{details}")
        return self.git("rev-parse", "HEAD")

    def check(self):
        head = self.preflight()
        self.git("fetch", "--no-tags", "origin", timeout=90)
        target = self.git("rev-parse", "@{upstream}")
        ahead, behind = map(int, self.git("rev-list", "--left-right", "--count", f"{head}...{target}").split())
        if ahead:
            raise UpdateError("Local commits are ahead of or diverged from upstream; manual review required.")
        summary = self.git("log", "--oneline", "-10", f"{head}..{target}") if behind else ""
        return UpdatePlan(head, target, behind, summary)

    def apply(self, plan):
        if not plan.behind:
            return
        if self.preflight() != plan.head or self.git("rev-parse", "@{upstream}") != plan.target:
            raise UpdateError("Repository changed since checking; check again.")
        changed = self.git("diff", "--name-only", plan.head, plan.target).splitlines()
        if any(name.startswith("requirements") and name.endswith(".txt") or
               name in ("pyproject.toml", "setup.py", "setup.cfg") for name in changed):
            raise UpdateError("Dependencies changed. Update manually and install the new requirements before restarting.")
        self.git("merge", "--ff-only", plan.target, timeout=60)
