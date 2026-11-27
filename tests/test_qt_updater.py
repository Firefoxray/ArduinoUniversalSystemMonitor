import unittest
from unittest.mock import Mock
from uasm_qt.updater import PreviewUpdater, UpdateError, UpdatePlan, UPSTREAM


class UpdaterTests(unittest.TestCase):
    def test_wrong_branch(self):
        updater = PreviewUpdater(".")
        updater.git = Mock(return_value="origin/main")
        with self.assertRaises(UpdateError):
            updater.check()
        self.assertEqual(updater.git.call_count, 1)

    def test_dirty_checkout(self):
        updater = PreviewUpdater(".")
        updater.git = Mock(side_effect=[UPSTREAM, " M tracked.py"])
        with self.assertRaises(UpdateError):
            updater.check()
        self.assertEqual(updater.git.call_count, 2)

    def test_fast_forward_plan(self):
        updater = PreviewUpdater(".")
        updater.git = Mock(side_effect=[UPSTREAM, "", "a", "", "b", "0 2", "b update"])
        self.assertEqual(updater.check(), UpdatePlan("a", "b", 2, "b update"))

    def test_apply_checks_dependencies(self):
        updater = PreviewUpdater(".")
        updater.git = Mock(side_effect=[UPSTREAM, "", "a", "b", "requirements-qt.txt"])
        with self.assertRaises(UpdateError):
            updater.apply(UpdatePlan("a", "b", 1, "update"))
        self.assertEqual(updater.git.call_count, 5)

    def test_apply_only_fast_forward(self):
        updater = PreviewUpdater(".")
        updater.git = Mock(side_effect=[UPSTREAM, "", "a", "b", "uasm_qt/app.py", ""])
        updater.apply(UpdatePlan("a", "b", 1, "update"))
        updater.git.assert_called_with("merge", "--ff-only", "b", timeout=60)

    def test_ahead_refused(self):
        updater = PreviewUpdater(".")
        updater.git = Mock(side_effect=[UPSTREAM, "", "a", "", "b", "1 2"])
        with self.assertRaises(UpdateError):
            updater.check()
