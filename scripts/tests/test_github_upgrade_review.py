import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from github_upgrade_review import open_draft, read_ci


class GitHubUpgradeReviewTest(unittest.TestCase):
    def setUp(self):
        self.base = "a" * 40
        self.head = "b" * 40
        self.github = Mock(repository="owner/repo", token="fixture")
        self.body = {"base": "main", "head": "upgrade", "draft": True,
                     "title": "exact candidate", "body": "review"}

    def test_ci_uses_only_matching_commit(self):
        self.github.request.return_value = {
            "total_count": 2,
            "workflow_runs": [{"id": 11, "head_sha": self.head, "conclusion": "success"},
                              {"id": 12, "head_sha": self.base, "conclusion": "success"}],
        }
        result = read_ci(self.github, self.head)
        self.assertEqual([run["id"] for run in result["runs"]], [11])
        self.assertIn("head_sha=" + self.head, self.github.request.call_args.args[0])

    def test_changed_remote_head_stops_before_finding_or_creating_pr(self):
        self.github.request.side_effect = [{"object": {"sha": self.base}}, {"object": {"sha": self.base}}]
        with self.assertRaisesRegex(ValueError, "不匹配"):
            open_draft(self.github, self.body, self.base, self.head)
        self.assertEqual(self.github.request.call_count, 2)

    def test_draft_creation_is_bound_to_verified_commit_and_is_reused(self):
        pr = {"number": 42, "draft": True, "html_url": "https://github.com/owner/repo/pull/42",
              "head": {"sha": self.head}, "base": {"sha": self.base}}
        self.github.request.side_effect = [{"object": {"sha": self.base}}, {"object": {"sha": self.head}}, [], pr]
        result = open_draft(self.github, self.body, self.base, self.head)
        self.assertFalse(result["reused"])
        self.github.request.assert_called_with("/pulls", self.body)
        self.github.request.reset_mock()
        self.github.request.side_effect = [{"object": {"sha": self.base}}, {"object": {"sha": self.head}}, [pr]]
        self.assertTrue(open_draft(self.github, self.body, self.base, self.head)["reused"])
        self.assertEqual(self.github.request.call_count, 3)


if __name__ == "__main__":
    unittest.main()
