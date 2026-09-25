#!/usr/bin/env python3
"""Tests for secret_belt.py and guard_commit_secrets.py.

    python3 .claude/hooks/test_guard_commit_secrets.py

Runs the real hook process against throwaway git repos. Every credential-shaped
fixture is assembled at runtime from fragments, so this file never contains a
literal one (it would trip the guard, and GitHub push protection, on itself).
Stdlib only, no network.
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
HOOK = os.path.join(HERE, "guard_commit_secrets.py")
sys.path.insert(0, HERE)
import secret_belt  # noqa: E402

# Fragments joined at runtime; none is credential-shaped on its own.
GH = "gh" + "p_" + "Zq3" * 12
AWS = "AK" + "IA" + "QW7RT2Y6UP4LM9ZX"
PEM = "-----BEGIN " + "RSA PRIVATE" + " KEY-----"
SK = "sk-" + "ant-" + "a1B2c3D4e5F6g7H8i9J0kLmN"


def git(repo, *args):
    subprocess.run(["git", *args], cwd=repo, check=True, capture_output=True)


class Repo:
    def __enter__(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = self.tmp.name
        git(self.path, "init", "-q")
        git(self.path, "config", "user.email", "t@example.invalid")
        git(self.path, "config", "user.name", "t")
        self.write("README.md", "hello\n")
        git(self.path, "add", "README.md")
        git(self.path, "commit", "-qm", "init")
        return self

    def __exit__(self, *exc):
        self.tmp.cleanup()

    def write(self, name, text, mode="w"):
        full = os.path.join(self.path, name)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, mode) as fh:
            fh.write(text)

    def stage(self, name, text):
        self.write(name, text)
        git(self.path, "add", name)

    def hook(self, command):
        event = {"tool_name": "Bash", "cwd": self.path,
                 "tool_input": {"command": command}}
        proc = subprocess.run([sys.executable, HOOK], input=json.dumps(event),
                              capture_output=True, text=True, timeout=30)
        self.last = proc.stdout
        return bool(proc.stdout.strip()) and json.loads(proc.stdout)[
            "hookSpecificOutput"]["permissionDecision"] == "deny"


class Belt(unittest.TestCase):
    def test_high_precision(self):
        for text in (GH, AWS, PEM, SK, "https://bob:" + "hunter2x" + "@host.test/x"):
            self.assertTrue(secret_belt.blocking_hits("k = " + text), text)

    def test_placeholders_and_lookalikes(self):
        for text in ("token: ghp_your_token_here", "export GITHUB_TOKEN=${{ secrets.GITHUB_TOKEN }}",
                     "risk-" + "a" * 30, "password: <redacted>", "sk-xxxxxxxxxxxxxxxxxxxxxxxx",
                     "the example key " + AWS, "gpr.key=$GITHUB_TOKEN"):
            self.assertEqual(secret_belt.blocking_hits(text), [], text)

    def test_only_four_chars_leak(self):
        hit = secret_belt.blocking_hits("x " + GH)[0]
        self.assertEqual(hit["redacted"], GH[:4] + "...")

    def test_key_files(self):
        self.assertTrue(secret_belt.is_key_file("testing/keystore/morphe.keystore"))
        self.assertTrue(secret_belt.is_key_file("release.jks"))
        self.assertFalse(secret_belt.is_key_file("experimental/autotest/profiles/netflix.env"))
        self.assertFalse(secret_belt.is_key_file("docs/keystore.md"))


class Hook(unittest.TestCase):
    def test_staged_secret_denied(self):
        with Repo() as r:
            r.stage("config.gradle", "gpr.key=" + GH + "\n")
            self.assertTrue(r.hook('git commit -m "x"'))
            self.assertIn("config.gradle:1 github_token ghp_...", r.last)
            self.assertNotIn(GH, r.last)

    def test_clean_commit_silent(self):
        with Repo() as r:
            r.stage("a.txt", "fine\n")
            self.assertFalse(r.hook('git commit -m "x"'))
            self.assertEqual(r.last, "")

    def test_allow_mark(self):
        with Repo() as r:
            r.stage("a.txt", "fixture = '" + AWS + "'  # secret-belt: allow\n")
            self.assertFalse(r.hook("git commit -m x"))

    def test_only_added_lines_count(self):
        with Repo() as r:
            r.stage("a.txt", "k=" + SK + "  # secret-belt: allow\n")
            git(r.path, "commit", "-qm", "seed")
            r.write("a.txt", "k=" + SK + "  # secret-belt: allow\nmore\n")
            git(r.path, "add", "a.txt")
            self.assertFalse(r.hook("git commit -m x"))

    def test_commit_all_includes_unstaged(self):
        with Repo() as r:
            r.write("README.md", "hello\n" + PEM + "\n")
            self.assertFalse(r.hook("git commit -m x"))
            self.assertTrue(r.hook("git commit -am x"))
            self.assertTrue(r.hook("git commit --all -m x"))

    def test_add_then_commit(self):
        with Repo() as r:
            r.write("new/creds.txt", "t=" + GH + "\n")
            r.write("other.txt", "fine\n")
            self.assertTrue(r.hook("git add -A && git commit -m x"))
            self.assertTrue(r.hook("git add new && git commit -m x"))
            self.assertFalse(r.hook("git add other.txt && git commit -m x"))

    def test_keystore_file(self):
        with Repo() as r:
            r.write("signing/release.keystore", b"\x00\x01binary", mode="wb")
            git(r.path, "add", "signing/release.keystore")
            self.assertTrue(r.hook("git commit -m x"))
            self.assertIn("keystore", r.last)

    def test_git_dash_c(self):
        with Repo() as r:
            r.stage("a.txt", "k=" + AWS + "\n")
            event_cwd = os.path.dirname(r.path)
            event = {"tool_name": "Bash", "cwd": event_cwd, "tool_input": {
                "command": "git -C %s commit -m x" % os.path.basename(r.path)}}
            out = subprocess.run([sys.executable, HOOK], input=json.dumps(event),
                                 capture_output=True, text=True).stdout
            self.assertIn("deny", out)

    def test_non_commit_commands_ignored(self):
        with Repo() as r:
            r.stage("a.txt", "k=" + AWS + "\n")
            for cmd in ("git status", "git log --grep commit", 'echo "git commit"',
                        "git diff --cached"):
                self.assertFalse(r.hook(cmd), cmd)

    def test_fails_open(self):
        with tempfile.TemporaryDirectory() as not_a_repo:
            event = {"cwd": not_a_repo, "tool_input": {"command": "git commit -m x"}}
            proc = subprocess.run([sys.executable, HOOK], input=json.dumps(event),
                                  capture_output=True, text=True)
            self.assertEqual((proc.returncode, proc.stdout), (0, ""))
        for raw in ("", "nope", "[]"):
            proc = subprocess.run([sys.executable, HOOK], input=raw,
                                  capture_output=True, text=True)
            self.assertEqual((proc.returncode, proc.stdout), (0, ""), raw)


class Cli(unittest.TestCase):
    def test_range(self):
        with Repo() as r:
            base = subprocess.run(["git", "rev-parse", "HEAD"], cwd=r.path,
                                  capture_output=True, text=True).stdout.strip()
            r.stage("a.txt", "k=" + GH + "\n")
            git(r.path, "commit", "-qm", "leak")
            proc = subprocess.run([sys.executable, HOOK, "--range", base + "..HEAD"],
                                  cwd=r.path, capture_output=True, text=True)
            self.assertEqual(proc.returncode, 1)
            self.assertIn("a.txt:1 github_token", proc.stdout)
            self.assertNotIn(GH, proc.stdout)
            proc = subprocess.run([sys.executable, HOOK, "--range", "HEAD..HEAD"],
                                  cwd=r.path, capture_output=True, text=True)
            self.assertEqual(proc.returncode, 0)


if __name__ == "__main__":
    unittest.main(verbosity=1)
