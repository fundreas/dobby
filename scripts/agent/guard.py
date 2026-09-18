#!/usr/bin/env python3
"""PreToolUse hook for the agent container: the only things it says no to.

Claude runs here with --dangerously-skip-permissions, so this hook is the whole
permission system. It sees every Bash call before it runs and blocks three
families, everything else passes untouched:

  * git push — in any spelling (git -C dir push, git --no-pager push, …)
  * git commands that throw work away: branch/tag deletion, reset --hard,
    checkout/restore that discard the working tree, clean, stash drop/clear,
    rm, commit --amend, reflog/gc pruning, worktree removal, remote removal,
    history rewriting, and rm -rf aimed at .git
  * anything that could brick or wipe the phone on the other end of adb:
    fastboot in any form, reboots into bootloader/recovery, factory reset,
    and adb shell commands that format or erase storage. Installing,
    uninstalling, clearing app data, logcat and screencap all stay allowed —
    that is the normal build/flash/debug loop.

Exit 2 blocks the call and hands the stderr text to Claude as the reason, so it
can pick a different route (or tell you it needs you to do the push).

Hook protocol: https://code.claude.com/docs/en/hooks
"""

import json
import re
import shlex
import sys

# git's own global options that sit between `git` and the subcommand. Ones that
# take a separate argument are listed with the count of args they swallow.
GIT_GLOBAL_OPTS_WITH_ARG = {"-C", "-c", "--git-dir", "--work-tree", "--namespace", "--exec-path", "--config-env"}
GIT_GLOBAL_FLAGS = {
    "--no-pager", "-p", "--paginate", "-P", "--bare", "--no-replace-objects",
    "--literal-pathspecs", "--glob-pathspecs", "--noglob-pathspecs",
    "--icase-pathspecs", "--no-optional-locks", "--no-lazy-fetch",
    "--no-advice", "--html-path", "--man-path", "--info-path",
}

# Wrappers that may precede the actual command word.
WRAPPERS = {"sudo", "env", "command", "exec", "nice", "nohup", "time", "xargs", "timeout", "doas"}


def split_simple_commands(tokens):
    """Split a shlex token list on shell separators into simple commands."""
    seps = {";", "&&", "||", "|", "&", "\n"}
    current, out = [], []
    for tok in tokens:
        if tok in seps:
            if current:
                out.append(current)
            current = []
        else:
            current.append(tok)
    if current:
        out.append(current)
    return out


def strip_wrappers(cmd):
    """Drop sudo/env/VAR=x prefixes so cmd[0] is the real program."""
    i = 0
    while i < len(cmd):
        tok = cmd[i]
        if tok in WRAPPERS or re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*", tok):
            i += 1
            continue
        if tok.startswith("-") and i > 0 and cmd[i - 1] in WRAPPERS:
            i += 1  # e.g. `env -i`, `sudo -u x`
            continue
        break
    return cmd[i:]


def git_subcommand(cmd):
    """Return (subcommand, args) if cmd is a git invocation, else None."""
    cmd = strip_wrappers(cmd)
    if not cmd:
        return None
    prog = cmd[0].rsplit("/", 1)[-1]
    if prog != "git":
        return None
    i = 1
    while i < len(cmd):
        tok = cmd[i]
        if tok in GIT_GLOBAL_OPTS_WITH_ARG:
            i += 2
        elif tok.split("=", 1)[0] in GIT_GLOBAL_OPTS_WITH_ARG or tok in GIT_GLOBAL_FLAGS or tok.startswith("--"):
            i += 1
        else:
            return tok, cmd[i + 1:]
    return None


def has_flag(args, *flags):
    for a in args:
        if a in flags:
            return True
        # combined short flags: -fd, -dr …
        if re.fullmatch(r"-[a-zA-Z]{2,}", a) and any(f.startswith("-") and not f.startswith("--") and f[1] in a[1:] for f in flags):
            return True
    return False


def check_git(sub, args):
    """Return a reason string if this git call must be blocked, else None."""
    if sub == "push":
        return "git push is disabled in the agent container: commit locally, Andreas pushes."
    if sub == "branch" and has_flag(args, "-d", "-D", "--delete"):
        return "deleting branches is disabled in the agent container."
    if sub == "tag" and has_flag(args, "-d", "--delete"):
        return "deleting tags is disabled in the agent container."
    if sub == "reset" and has_flag(args, "--hard", "--merge"):
        return "git reset --hard/--merge throws away working-tree changes; use git stash or a plain git reset instead."
    if sub == "checkout" and (has_flag(args, "-f", "--force") or "--" in args or "." in args):
        return "git checkout that discards working-tree changes is disabled; stash them instead."
    if sub == "restore" and not (has_flag(args, "--staged", "-S") and not has_flag(args, "--worktree", "-W")):
        return "git restore discards working-tree changes; only `git restore --staged` (unstage) is allowed."
    if sub == "clean":
        return "git clean deletes untracked files and is disabled in the agent container."
    if sub == "stash" and any(a in ("drop", "clear") for a in args):
        return "git stash drop/clear is disabled in the agent container."
    if sub == "rm" and not has_flag(args, "--cached"):
        return "git rm deletes files; use `git rm --cached` to unstage, or plain rm for a file you own."
    if sub == "commit" and has_flag(args, "--amend"):
        return "git commit --amend rewrites a commit; make a new commit instead."
    if sub == "reflog" and any(a in ("expire", "delete") for a in args):
        return "git reflog expire/delete destroys the safety net and is disabled."
    if sub in ("gc", "prune", "repack") and (sub != "gc" or has_flag(args, "--prune") or any(a.startswith("--prune") for a in args)):
        return "pruning unreachable objects is disabled in the agent container."
    if sub == "update-ref" and has_flag(args, "-d", "--delete"):
        return "deleting refs is disabled in the agent container."
    if sub == "symbolic-ref" and has_flag(args, "-d", "--delete"):
        return "deleting refs is disabled in the agent container."
    if sub == "worktree" and any(a in ("remove", "prune") for a in args):
        return "removing worktrees is disabled in the agent container."
    if sub == "remote" and any(a in ("remove", "rm", "prune") for a in args):
        return "removing remotes is disabled in the agent container."
    if sub in ("filter-branch", "filter-repo", "replace"):
        return f"git {sub} rewrites history and is disabled in the agent container."
    return None


# adb's own options that sit between `adb` and the subcommand.
ADB_OPTS_WITH_ARG = {"-s", "-t", "-H", "-P", "-L", "--serial", "--transport-id", "--one-device"}
ADB_FLAGS = {"-d", "-e", "-a"}

# Words that mean "erase the device" wherever they show up in an adb shell line.
DEVICE_WIPE_PATTERNS = [
    (r"\b(recovery|bootloader|fastboot|edl|download)\b", "rebooting the phone out of Android"),
    (r"--wipe-data|\bwipe\b|\bfactory[-_ ]?reset\b", "wiping the phone"),
    (r"\bmake_ext4fs\b|\bmkfs\b|\bformat\b\s+/", "formatting device storage"),
    (r"\brm\b[^|;&]*\s/(sdcard|data|system|vendor|storage)\b", "deleting device storage"),
    (r"\bdd\b[^|;&]*\bof=/dev/block", "writing raw blocks to the phone"),
]


def check_device(cmd):
    """Return a reason string if this call could brick or wipe the phone."""
    cmd = strip_wrappers(cmd)
    if not cmd:
        return None
    prog = cmd[0].rsplit("/", 1)[-1]

    if prog in ("fastboot", "heimdall", "odin"):
        return (f"{prog} talks to the phone's bootloader and is disabled in the agent container: "
                "flashing, erasing and unlocking are Andreas's call.")
    if prog != "adb":
        return None

    i = 1
    while i < len(cmd):
        tok = cmd[i]
        if tok in ADB_OPTS_WITH_ARG:
            i += 2
        elif tok.split("=", 1)[0] in ADB_OPTS_WITH_ARG or tok in ADB_FLAGS:
            i += 1
        else:
            break
    sub, args = (cmd[i], cmd[i + 1:]) if i < len(cmd) else (None, [])
    rest = " ".join(args)

    if sub in ("reboot", "reboot-bootloader", "reboot-recovery"):
        target = args[0] if args else ""
        if sub != "reboot" or target:
            return ("rebooting the phone into the bootloader or recovery is disabled in the agent "
                    "container; a plain `adb reboot` is allowed.")
        return None
    if sub in ("root", "unroot", "remount", "disable-verity", "enable-verity", "sideload"):
        return f"adb {sub} changes how the phone boots and is disabled in the agent container."
    if sub == "shell":
        for pattern, what in DEVICE_WIPE_PATTERNS:
            if re.search(pattern, rest, re.IGNORECASE):
                return (f"this adb shell command looks like {what}, which is disabled in the agent "
                        "container. Install, uninstall, `pm clear`, logcat and screencap are fine.")
    return None


def check_rm(cmd):
    cmd = strip_wrappers(cmd)
    if not cmd or cmd[0].rsplit("/", 1)[-1] not in ("rm", "rmdir", "shred", "unlink"):
        return None
    for a in cmd[1:]:
        if re.search(r"(^|/)\.git(/|$)", a):
            return "removing the .git directory (or anything inside it) is disabled in the agent container."
    return None


# Coarse fallback for commands shlex cannot parse (unbalanced quotes, heredocs).
FALLBACK_PATTERNS = [
    (r"\bgit\b[^|;&\n]*\bpush\b", "git push is disabled in the agent container."),
    (r"\bgit\b[^|;&\n]*\breset\b[^|;&\n]*--hard", "git reset --hard is disabled in the agent container."),
    (r"\bgit\b[^|;&\n]*\bclean\b", "git clean is disabled in the agent container."),
    (r"\bgit\b[^|;&\n]*\bbranch\b[^|;&\n]*\s-[dD]\b", "deleting branches is disabled in the agent container."),
    (r"\bgit\b[^|;&\n]*\bstash\b[^|;&\n]*\b(drop|clear)\b", "git stash drop/clear is disabled in the agent container."),
    (r"\brm\b[^|;&\n]*\.git\b", "removing .git is disabled in the agent container."),
    (r"\bfastboot\b", "fastboot is disabled in the agent container."),
    (r"\badb\b[^|;&\n]*\breboot\b[^|;&\n]*\b(bootloader|recovery|edl)\b",
     "rebooting the phone into the bootloader or recovery is disabled in the agent container."),
]


def inspect(command):
    try:
        tokens = shlex.split(command, comments=False, posix=True)
    except ValueError:
        tokens = None

    if tokens is None:
        # Unparseable (unbalanced quote, heredoc): fall back to coarse regexes.
        for pattern, reason in FALLBACK_PATTERNS:
            if re.search(pattern, command):
                return reason
        return None

    for simple in split_simple_commands(tokens):
        git = git_subcommand(simple)
        if git:
            reason = check_git(*git)
            if reason:
                return reason
        for check in (check_rm, check_device):
            reason = check(simple)
            if reason:
                return reason
    return None


def main():
    try:
        payload = json.load(sys.stdin)
    except json.JSONDecodeError:
        return 0
    if payload.get("tool_name") != "Bash":
        return 0
    command = (payload.get("tool_input") or {}).get("command") or ""
    # Normalise separators so `a;b` and `a&&b` split even without spaces.
    normalised = re.sub(r"(\|\||&&|;|\||\n)", r" \1 ", command)
    reason = inspect(normalised)
    if reason:
        sys.stderr.write(f"BLOCKED by scripts/agent/guard.py: {reason}\n")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
