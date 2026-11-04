import unittest

from check_whitespace import git_check_command


class WhitespaceCommandTests(unittest.TestCase):
    def test_pr_base_takes_priority(self):
        sha = "a" * 40
        self.assertEqual(
            ["git", "diff", "--check", sha, "HEAD"],
            git_check_command({"KT_PR_BASE": sha, "KT_PUSH_BASE": "b" * 40}),
        )

    def test_push_checks_full_committed_change(self):
        sha = "a" * 40
        self.assertEqual(["git", "diff", "--check", sha, "HEAD"], git_check_command({"KT_PUSH_BASE": sha}))

    def test_new_branch_and_manual_runs_check_head_commit(self):
        for env in [{}, {"KT_PUSH_BASE": "0" * 40}]:
            self.assertEqual(["git", "show", "--format=", "--check", "HEAD"], git_check_command(env))

    def test_argument_injection_rejected(self):
        with self.assertRaises(ValueError):
            git_check_command({"KT_PR_BASE": "--output=unwanted"})


if __name__ == "__main__":
    unittest.main()
