package com.hunt.otziv.reputationai.infrastructure.web;

import java.util.List;
import java.util.Set;

public interface WebsiteCrawler {

    List<CrawledPage> crawl(List<String> urls);

    default List<CrawledPage> crawl(List<String> urls, Set<String> deepCrawlHosts) {
        return crawl(urls);
    }
}
