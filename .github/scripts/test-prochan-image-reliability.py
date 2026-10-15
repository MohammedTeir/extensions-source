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

    def test_non_image_requests_bypass_recovery(self):
        self.assertIn("if (fallback == null) return@Interceptor chain.proceed(request)", self.code)

    def test_bounded_retry(self):
        self.assertIn("private const val MAX_IMAGE_RETRIES = 1", self.code)
        self.assertIn("for (attempt in 0..MAX_IMAGE_RETRIES)", self.code)

    def test_fallback_on_http_failures(self):
        self.assertIn("response.code == 403", self.code)
        self.assertIn("response.code == 404", self.code)
        self.assertIn("response.code == 429", self.code)
        self.assertIn("response.code in 500..599", self.code)

    def test_failed_responses_closed_before_retry(self):
        self.assertGreaterEqual(self.code.count("response.close()"), 2)

    def test_stale_search_requests_cannot_overwrite_cache(self):
        self.assertIn("private val searchGeneration = java.util.concurrent.atomic.AtomicLong()", self.code)
        self.assertIn("val generation = searchGeneration.incrementAndGet()", self.code)
        self.assertIn("if (activeSearchGeneration == generation)", self.code)
        self.assertIn("fetchFilteredSearchPage(next, start, end, query, filters, generation)", self.code)

    def test_url_normalization(self):
        self.assertIn('value.startsWith("//") -> "https:$value"', self.code)
        self.assertIn('value.startsWith("/") -> "https://app.procomic.pro$value"', self.code)


if __name__ == "__main__":
    unittest.main()
