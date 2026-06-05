package com.rsscopilot.server.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.rsscopilot.server.config.AppProperties;
import com.rsscopilot.server.http.SimpleHttpClient;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class ArticleContentExtractorUnitTest {

  @Test
  void shouldResolveRelativeContentUrlsBeforePersistingArticleHtml() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      String articleUrl = server.url("/posts/relative").toString();
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/html; charset=utf-8")
              .setBody(
                  """
                  <html>
                    <body>
                      <nav>Navigation</nav>
                      <article>
                        <h1>Relative Article</h1>
                        <p><a href="../about">About the source</a></p>
                        <img src="data:image/gif;base64,R0lGODlhAQABAAAAACw="
                             data-src="../images/cover.webp"
                             srcset="../images/small.jpg 320w, ../images/large.jpg 960w" />
                        <picture>
                          <source srcset="../media/small.webp 480w, ../media/large.webp 960w" />
                          <source data-srcset="../media/lazy-small.avif 480w, ../media/lazy-large.avif 960w"
                                  type="image/avif" />
                        </picture>
                      </article>
                    </body>
                  </html>
                  """));

      ArticleContentExtractor extractor =
          new ArticleContentExtractor(new SimpleHttpClient(), new AppProperties());
      ArticleContent content = extractor.extract(articleUrl, "<p>Fallback</p>", "Fallback");

      assertThat(content.fetched()).isTrue();
      assertThat(content.text()).contains("Relative Article", "About the source");
      assertThat(content.coverImageUrl()).isEqualTo(server.url("/images/cover.webp").toString());
      assertThat(content.html()).contains("href=\"" + server.url("/about") + "\"");
      assertThat(content.html()).contains("src=\"" + server.url("/images/cover.webp") + "\"");
      assertThat(content.html()).contains(server.url("/images/small.jpg") + " 320w");
      assertThat(content.html()).contains(server.url("/images/large.jpg") + " 960w");
      assertThat(content.html()).contains(server.url("/media/small.webp") + " 480w");
      assertThat(content.html()).contains(server.url("/media/lazy-large.avif") + " 960w");
      assertThat(content.html()).doesNotContain("data-srcset");
      assertThat(content.html()).doesNotContain("Navigation");
    } finally {
      shutdown(server);
    }
  }

  @Test
  void shouldUseOpenGraphImageWhenArticleBodyHasNoImage() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      String articleUrl = server.url("/posts/meta-cover").toString();
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/html; charset=utf-8")
              .setBody(
                  """
                  <html>
                    <head>
                      <meta property="og:image" content="/images/social-cover.jpg" />
                    </head>
                    <body>
                      <article>
                        <h1>Meta Cover Article</h1>
                        <p>Article body without inline images.</p>
                      </article>
                    </body>
                  </html>
                  """));

      ArticleContentExtractor extractor =
          new ArticleContentExtractor(new SimpleHttpClient(), new AppProperties());
      ArticleContent content = extractor.extract(articleUrl, "<p>Fallback</p>", "Fallback");

      assertThat(content.fetched()).isTrue();
      assertThat(content.text()).contains("Meta Cover Article");
      assertThat(content.coverImageUrl())
          .isEqualTo(server.url("/images/social-cover.jpg").toString());
    } finally {
      shutdown(server);
    }
  }

  @Test
  void shouldPreserveArticleHeaderWhileRemovingNavigationChrome() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      String articleUrl = server.url("/posts/article-header").toString();
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/html; charset=utf-8")
              .setBody(
                  """
                  <html>
                    <body>
                      <header>Site masthead</header>
                      <article>
                        <nav>Article breadcrumb</nav>
                        <header>
                          <h1>Preserved Article Title</h1>
                          <p>By Jane Analyst</p>
                        </header>
                        <p>The actual article body.</p>
                      </article>
                      <footer>Site footer</footer>
                    </body>
                  </html>
                  """));

      ArticleContentExtractor extractor =
          new ArticleContentExtractor(new SimpleHttpClient(), new AppProperties());
      ArticleContent content = extractor.extract(articleUrl, "<p>Fallback</p>", "Fallback");

      assertThat(content.fetched()).isTrue();
      assertThat(content.text()).contains("Preserved Article Title", "By Jane Analyst");
      assertThat(content.text()).contains("The actual article body.");
      assertThat(content.text())
          .doesNotContain("Site masthead", "Site footer", "Article breadcrumb");
    } finally {
      shutdown(server);
    }
  }

  @Test
  void shouldPreferCommonArticleBodyContainerWhenSemanticTagsAreMissing() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      String articleUrl = server.url("/posts/common-container").toString();
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/html; charset=utf-8")
              .setBody(
                  """
                  <html>
                    <body>
                      <div class="site-shell">
                        <div class="hero">Newsletter masthead</div>
                        <div class="entry-content">
                          <h1>Container Article Title</h1>
                          <p>The actual article paragraph.</p>
                          <img src="/images/container-cover.jpg" />
                        </div>
                        <section class="comments">
                          <h2>Comments</h2>
                          <p>This discussion should not be persisted.</p>
                        </section>
                      </div>
                    </body>
                  </html>
                  """));

      ArticleContentExtractor extractor =
          new ArticleContentExtractor(new SimpleHttpClient(), new AppProperties());
      ArticleContent content = extractor.extract(articleUrl, "<p>Fallback</p>", "Fallback");

      assertThat(content.fetched()).isTrue();
      assertThat(content.text())
          .contains("Container Article Title", "The actual article paragraph");
      assertThat(content.text()).doesNotContain("Newsletter masthead", "Comments");
      assertThat(content.coverImageUrl())
          .isEqualTo(server.url("/images/container-cover.jpg").toString());
    } finally {
      shutdown(server);
    }
  }

  @Test
  void shouldRecognizeNewsAndBlogSpecificContentContainers() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      String articleUrl = server.url("/posts/news-container").toString();
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/html; charset=utf-8")
              .setBody(
                  """
                  <html>
                    <body>
                      <div class="page-shell">
                        <div class="top-stories">Top stories should stay out.</div>
                        <div class="postArticle-content">
                          <h1>Specific News Container</h1>
                          <p>The article paragraph from a Medium-style page.</p>
                          <p>Another paragraph that should be readable.</p>
                        </div>
                        <div class="more-stories">
                          <p>Recommended story should be removed.</p>
                        </div>
                      </div>
                    </body>
                  </html>
                  """));

      ArticleContentExtractor extractor =
          new ArticleContentExtractor(new SimpleHttpClient(), new AppProperties());
      ArticleContent content = extractor.extract(articleUrl, "<p>Fallback</p>", "Fallback");

      assertThat(content.fetched()).isTrue();
      assertThat(content.text())
          .contains(
              "Specific News Container",
              "The article paragraph from a Medium-style page",
              "Another paragraph");
      assertThat(content.text()).doesNotContain("Top stories", "Recommended story");
    } finally {
      shutdown(server);
    }
  }

  @Test
  void shouldPreferGenericContentIdBeforeWholeBodyFallback() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      String articleUrl = server.url("/posts/generic-content-id").toString();
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/html; charset=utf-8")
              .setBody(
                  """
                  <html>
                    <body>
                      <header>Global masthead</header>
                      <div id="content">
                        <h1>Generic Content Article</h1>
                        <p>This old-school blog stores the readable post in content.</p>
                      </div>
                      <aside>Sidebar links should not appear.</aside>
                    </body>
                  </html>
                  """));

      ArticleContentExtractor extractor =
          new ArticleContentExtractor(new SimpleHttpClient(), new AppProperties());
      ArticleContent content = extractor.extract(articleUrl, "<p>Fallback</p>", "Fallback");

      assertThat(content.fetched()).isTrue();
      assertThat(content.text()).contains("Generic Content Article", "readable post in content");
      assertThat(content.text()).doesNotContain("Global masthead", "Sidebar links");
    } finally {
      shutdown(server);
    }
  }

  @Test
  void shouldPreferSpecificArticleContainerInsideBroadMainContent() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      String articleUrl = server.url("/posts/main-with-related").toString();
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/html; charset=utf-8")
              .setBody(
                  """
                  <html>
                    <body>
                      <main>
                        <div class="entry-content">
                          <h1>Specific Article Title</h1>
                          <p>The focused article body.</p>
                        </div>
                        <section class="related">
                          <h2>Related stories</h2>
                          <p>Another headline that should stay outside the reader.</p>
                        </section>
                      </main>
                    </body>
                  </html>
                  """));

      ArticleContentExtractor extractor =
          new ArticleContentExtractor(new SimpleHttpClient(), new AppProperties());
      ArticleContent content = extractor.extract(articleUrl, "<p>Fallback</p>", "Fallback");

      assertThat(content.fetched()).isTrue();
      assertThat(content.text()).contains("Specific Article Title", "The focused article body");
      assertThat(content.text()).doesNotContain("Related stories", "Another headline");
    } finally {
      shutdown(server);
    }
  }

  @Test
  void shouldRemoveCommonNonArticleBlocksInsideArticleContainer() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      String articleUrl = server.url("/posts/noisy-article").toString();
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/html; charset=utf-8")
              .setBody(
                  """
                  <html>
                    <body>
                      <article>
                        <header>
                          <h1>Clean Article Title</h1>
                          <p>By Jane Analyst</p>
                        </header>
                        <p>The article body that should remain.</p>
                        <div class="share-buttons">Share this story</div>
                        <section class="related-posts">
                          <h2>Related posts</h2>
                          <p>Recommended headline.</p>
                        </section>
                        <div id="comments">Reader comments should not appear.</div>
                        <div class="newsletter-signup">Subscribe to the newsletter.</div>
                      </article>
                    </body>
                  </html>
                  """));

      ArticleContentExtractor extractor =
          new ArticleContentExtractor(new SimpleHttpClient(), new AppProperties());
      ArticleContent content = extractor.extract(articleUrl, "<p>Fallback</p>", "Fallback");

      assertThat(content.fetched()).isTrue();
      assertThat(content.text())
          .contains(
              "Clean Article Title", "By Jane Analyst", "The article body that should remain");
      assertThat(content.text())
          .doesNotContain(
              "Share this story",
              "Related posts",
              "Recommended headline",
              "Reader comments",
              "Subscribe to the newsletter");
    } finally {
      shutdown(server);
    }
  }

  @Test
  void shouldPreferStructuredArticleBodyWhenPageOnlyHasGenericBody() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      String articleUrl = server.url("/posts/structured-only").toString();
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/html; charset=utf-8")
              .setBody(
                  """
                  <html>
                    <head>
                      <script type="application/ld+json">
                        {
                          "@context": "https://schema.org",
                          "@type": "NewsArticle",
                          "headline": "Structured Article Title",
                          "image": {"url": "/images/structured-cover.jpg"},
                          "articleBody": "First clean paragraph.\\n\\nSecond clean paragraph."
                        }
                      </script>
                    </head>
                    <body>
                      <nav>Top navigation</nav>
                      <div class="layout-shell">
                        <h1>Structured Article Title</h1>
                        <p>Rendered page summary.</p>
                        <aside>Popular stories should not enter the reader.</aside>
                      </div>
                    </body>
                  </html>
                  """));

      ArticleContentExtractor extractor =
          new ArticleContentExtractor(new SimpleHttpClient(), new AppProperties());
      ArticleContent content = extractor.extract(articleUrl, "<p>Fallback</p>", "Fallback");

      assertThat(content.fetched()).isTrue();
      assertThat(content.text())
          .contains("Structured Article Title", "First clean paragraph", "Second clean paragraph");
      assertThat(content.text()).doesNotContain("Top navigation", "Popular stories");
      assertThat(content.html()).contains("<h1>Structured Article Title</h1>");
      assertThat(content.html()).contains("<p>First clean paragraph.</p>");
      assertThat(content.coverImageUrl())
          .isEqualTo(server.url("/images/structured-cover.jpg").toString());
    } finally {
      shutdown(server);
    }
  }

  @Test
  void shouldKeepSemanticArticleHtmlOverStructuredFallback() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      String articleUrl = server.url("/posts/semantic-with-json-ld").toString();
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/html; charset=utf-8")
              .setBody(
                  """
                  <html>
                    <head>
                      <script type="application/ld+json">
                        {
                          "@context": "https://schema.org",
                          "@type": ["BlogPosting"],
                          "headline": "Structured Title",
                          "articleBody": "Plain structured body."
                        }
                      </script>
                    </head>
                    <body>
                      <article>
                        <h1>Rendered Article Title</h1>
                        <figure>
                          <img src="/images/rendered-cover.jpg" />
                          <figcaption>Rendered caption</figcaption>
                        </figure>
                        <p>The rendered article keeps richer inline media.</p>
                      </article>
                    </body>
                  </html>
                  """));

      ArticleContentExtractor extractor =
          new ArticleContentExtractor(new SimpleHttpClient(), new AppProperties());
      ArticleContent content = extractor.extract(articleUrl, "<p>Fallback</p>", "Fallback");

      assertThat(content.fetched()).isTrue();
      assertThat(content.text())
          .contains("Rendered Article Title", "Rendered caption", "richer inline media");
      assertThat(content.text()).doesNotContain("Plain structured body");
      assertThat(content.coverImageUrl())
          .isEqualTo(server.url("/images/rendered-cover.jpg").toString());
    } finally {
      shutdown(server);
    }
  }

  @Test
  void shouldSanitizeFetchedArticleHtmlBeforePersisting() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      String articleUrl = server.url("/posts/unsafe").toString();
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/html; charset=utf-8")
              .setBody(
                  """
                  <html>
                    <body>
                      <article>
                        <h1>Unsafe Article</h1>
                        <p onclick="steal()">Readable paragraph</p>
                        <a href="javascript:alert(1)" onmouseover="steal()">bad link</a>
                        <img src="javascript:alert(2)" onerror="steal()" data-src="/safe.png" />
                        <script>alert(3)</script>
                      </article>
                    </body>
                  </html>
                  """));

      ArticleContentExtractor extractor =
          new ArticleContentExtractor(new SimpleHttpClient(), new AppProperties());
      ArticleContent content = extractor.extract(articleUrl, "<p>Fallback</p>", "Fallback");

      assertThat(content.fetched()).isTrue();
      assertThat(content.html()).contains("Unsafe Article", "Readable paragraph");
      assertThat(content.html()).contains("src=\"" + server.url("/safe.png") + "\"");
      assertThat(content.html())
          .doesNotContain("onclick", "onmouseover", "onerror", "javascript:", "<script");
      assertThat(content.text()).doesNotContain("alert(3)");
    } finally {
      shutdown(server);
    }
  }

  @Test
  void shouldSanitizeFallbackHtmlWhenArticleFetchFails() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      String articleUrl = server.url("/posts/missing").toString();
      server.enqueue(new MockResponse().setResponseCode(500));

      ArticleContentExtractor extractor =
          new ArticleContentExtractor(new SimpleHttpClient(), new AppProperties());
      ArticleContent content =
          extractor.extract(
              articleUrl,
              """
              <p onclick="steal()">Fallback summary</p>
              <a href="javascript:alert(1)">bad link</a>
              <script>alert(2)</script>
              """,
              "Fallback summary");

      assertThat(content.fetched()).isFalse();
      assertThat(content.html()).contains("Fallback summary");
      assertThat(content.html()).doesNotContain("onclick", "javascript:", "<script");
      assertThat(content.text()).doesNotContain("alert(2)");
    } finally {
      shutdown(server);
    }
  }

  @Test
  void shouldResolveRelativeFallbackContentUrlsWhenArticleFetchFails() throws Exception {
    MockWebServer server = new MockWebServer();
    server.start();
    try {
      String articleUrl = server.url("/posts/missing-relative").toString();
      server.enqueue(new MockResponse().setResponseCode(503));

      ArticleContentExtractor extractor =
          new ArticleContentExtractor(new SimpleHttpClient(), new AppProperties());
      ArticleContent content =
          extractor.extract(
              articleUrl,
              """
              <p>Fallback full article from the feed.</p>
              <a href="../about">About this source</a>
              <img src="data:image/gif;base64,R0lGODlhAQABAAAAACw="
                   data-src="../images/feed-cover.webp"
                   srcset="../images/feed-small.jpg 320w, ../images/feed-large.jpg 960w" />
              """,
              "Fallback full article from the feed.");

      assertThat(content.fetched()).isFalse();
      assertThat(content.text()).contains("Fallback full article", "About this source");
      assertThat(content.coverImageUrl())
          .isEqualTo(server.url("/images/feed-cover.webp").toString());
      assertThat(content.html()).contains("href=\"" + server.url("/about") + "\"");
      assertThat(content.html()).contains("src=\"" + server.url("/images/feed-cover.webp") + "\"");
      assertThat(content.html()).contains(server.url("/images/feed-small.jpg") + " 320w");
      assertThat(content.html()).contains(server.url("/images/feed-large.jpg") + " 960w");
      assertThat(content.html()).doesNotContain("data:image");
    } finally {
      shutdown(server);
    }
  }

  private void shutdown(MockWebServer server) throws IOException {
    server.shutdown();
  }
}
