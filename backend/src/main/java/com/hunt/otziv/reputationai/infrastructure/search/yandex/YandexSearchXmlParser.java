package com.hunt.otziv.reputationai.infrastructure.search.yandex;

import com.hunt.otziv.reputationai.infrastructure.search.SearchResult;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

@Component
public class YandexSearchXmlParser {

    public List<SearchResult> parse(String xml, int limit) {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);

            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList docs = document.getElementsByTagName("doc");
            List<SearchResult> results = new ArrayList<>();

            for (int i = 0; i < docs.getLength() && results.size() < limit; i++) {
                Node doc = docs.item(i);
                String url = textOfFirst(doc, "url");
                if (url.isBlank()) {
                    continue;
                }

                String title = textOfFirst(doc, "title");
                String snippet = firstNonBlank(
                        textOfFirst(doc, "headline"),
                        textOfFirst(doc, "passage")
                );

                results.add(new SearchResult(title, url, snippet, "yandex"));
            }

            return results;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String textOfFirst(Node root, String tagName) {
        if (root == null) {
            return "";
        }

        NodeList nodes = asElementNodeList(root, tagName);
        if (nodes.getLength() == 0) {
            return "";
        }

        return normalize(nodes.item(0).getTextContent());
    }

    private NodeList asElementNodeList(Node root, String tagName) {
        if (root instanceof org.w3c.dom.Element element) {
            return element.getElementsByTagName(tagName);
        }

        return new EmptyNodeList();
    }

    private String firstNonBlank(String first, String second) {
        return !first.isBlank() ? first : second;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static class EmptyNodeList implements NodeList {
        @Override
        public Node item(int index) {
            return null;
        }

        @Override
        public int getLength() {
            return 0;
        }
    }
}
