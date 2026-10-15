#!/usr/bin/env python3
"""Structural regression checks for ProChan image recovery wiring.

These checks protect the source contract; they do not replace Android runtime
tests with mock HTTP responses or live CDN compatibility tests.
"""
from pathlib import Path
import unittest

SOURCE = Path("src/ar/mangapro/src/eu/kanade/tachiyomi/extension/ar/mangapro/ProChan.kt")


class ImageReliabilityContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.code = SOURCE.read_text(encoding="utf-8")

    def test_desktop_to_mobile_fallback_registered(self):
        self.assertIn("imageFallbacks[desktop] = mobile", self.code)
        self.assertIn("desktop ?: mobile", self.code)

    def test_executable_interceptor_wiring(self):
        self.assertIn("ProChanImageRecoveryInterceptor { imageFallbacks[it] }", self.code)
        interceptor = Path("src/ar/mangapro/src/eu/kanade/tachiyomi/extension/ar/mangapro/ProChanImageRecoveryInterceptor.kt").read_text(encoding="utf-8")
        self.assertIn("response.close()", interceptor)
        self.assertIn("for (attempt in 0..MAX_IMAGE_RETRIES)", interceptor)

    def test_stale_search_requests_cannot_overwrite_cache(self):
        self.assertIn("private val searchGeneration = java.util.concurrent.atomic.AtomicLong()", self.code)
        self.assertIn("val generation = searchGeneration.incrementAndGet()", self.code)
        self.assertIn("if (searchGeneration.get() == generation)", self.code)
        self.assertIn("fetchFilteredSearchPage(next, start, end, query, filters, generation)", self.code)

    def test_url_normalization(self):
        self.assertIn("LEGACY_IMAGE_HOST_REGEX", self.code)
        self.assertIn("RegexOption.IGNORE_CASE", self.code)
        self.assertIn('value.startsWith("//") -> "https:$value"', self.code)
        self.assertIn('value.startsWith("/") -> "https://app.procomic.pro$value"', self.code)


if __name__ == "__main__":
    unittest.main()
