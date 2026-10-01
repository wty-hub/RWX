import unittest

from analyze_vulkan_matrix import presentation_window


class PresentationWindowTest(unittest.TestCase):
    def test_repeat_at_window_boundary_uses_previous_accepted_frame(self):
        trace = [(0, 1, 10), (1_000_000_000, 1, 10), (2_000_000_000, 1, 11),
                 (3_000_000_000, 2, 11), (4_000_000_000, 2, 11)]
        window = presentation_window(trace, {"sampleStartNanos": 1_000_000_000, "sampleEndNanos": 4_000_000_000})
        self.assertEqual(window["acceptedPresentFps"], 4 / 3)
        self.assertEqual(window["freshSnapshotHz"], 2 / 3)
        self.assertEqual(window["repeatRatio"], .5)
        self.assertEqual(window["presentIntervalP95Ms"], 1000)

    def test_bad_window_or_unordered_trace_is_rejected(self):
        with self.assertRaises(ValueError):
            presentation_window([], {"sampleStartNanos": 10, "sampleEndNanos": 10})
        with self.assertRaises(ValueError):
            presentation_window([(20, 1, 1), (10, 1, 2)], {"sampleStartNanos": 0, "sampleEndNanos": 30})

    def test_empty_window_has_no_fabricated_freshness_or_percentile(self):
        window = presentation_window([(0, 1, 1)], {"sampleStartNanos": 1, "sampleEndNanos": 100})
        self.assertEqual(window["acceptedPresentFps"], 0)
        self.assertEqual(window["freshSnapshotHz"], 0)
        self.assertIsNone(window["repeatRatio"])
        self.assertIsNone(window["presentIntervalP99Ms"])


if __name__ == "__main__":
    unittest.main()
