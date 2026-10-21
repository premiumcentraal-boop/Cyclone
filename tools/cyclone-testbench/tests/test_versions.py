import unittest

from cyclone_testbench.versions import VersionError, at_least, parse


class VersionOrder(unittest.TestCase):
    def test_the_runtime_that_broke_the_rig_is_refused(self):
        # The PC runtime was 143 while the packs needed the page check from 151.
        self.assertFalse(at_least("5.0.0-alpha.143.dev1", "5.0.0-alpha.151.dev1"))

    def test_the_same_version_is_enough(self):
        self.assertTrue(at_least("5.0.0-alpha.151.dev1", "5.0.0-alpha.151.dev1"))

    def test_a_later_alpha_is_enough(self):
        self.assertTrue(at_least("5.0.0-alpha.152.dev1", "5.0.0-alpha.151.dev1"))

    def test_a_later_dev_build_of_the_same_alpha_is_enough(self):
        self.assertTrue(at_least("5.0.0-alpha.151.dev2", "5.0.0-alpha.151.dev1"))
        self.assertFalse(at_least("5.0.0-alpha.151.dev1", "5.0.0-alpha.151.dev2"))

    def test_a_release_is_newer_than_any_alpha_of_the_same_numbers(self):
        self.assertTrue(at_least("5.0.0", "5.0.0-alpha.999.dev9"))
        self.assertFalse(at_least("5.0.0-alpha.999.dev9", "5.0.0"))

    def test_numbers_compare_as_numbers_not_text(self):
        # Text order would put 9 after 10; a version gate must not.
        self.assertTrue(at_least("5.0.0-alpha.10.dev1", "5.0.0-alpha.9.dev1"))

    def test_text_that_is_not_a_version_is_refused_not_guessed(self):
        for bad in ["", "latest", "5.0", "5.0.0-beta.1", "v5.0.0-alpha.151.dev1", "5.0.0-alpha.151.dev1x"]:
            with self.assertRaises(VersionError, msg=bad):
                parse(bad)

    def test_surrounding_space_is_ignored(self):
        self.assertEqual((5, 0, 0, 151, 1), parse("  5.0.0-alpha.151.dev1 "))


if __name__ == "__main__":
    unittest.main()
