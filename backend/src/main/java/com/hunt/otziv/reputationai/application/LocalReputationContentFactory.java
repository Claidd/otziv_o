package com.hunt.otziv.reputationai.application;

import com.hunt.otziv.reputationai.api.dto.ReputationContentPackRequest;
import com.hunt.otziv.reputationai.domain.CompanyAiProfile;
import com.hunt.otziv.reputationai.domain.CompanySource;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LocalReputationContentFactory {

    private static final Pattern FACT_PATTERN = Pattern.compile(
            "(?iu)(?:\\b\\d[\\d\\s-]*\\+|\\b\\d[\\d\\s-]*(?:₽|руб\\.?|человек|персон|комнат|квест|отзыв|оценк|лет)\\b|(?:ул\\.?|улица|проспект|мкр\\.?|микрорайон)\\s*[^.!,;]{2,80}|рейтинг\\s*\\d[,.]\\d|\\d[,.]\\d\\s*(?:из\\s*5|—|-)\\s*средн)"
    );

    public ReputationContentPack create(ResearchSnapshot snapshot, ReputationContentPackRequest request) {
        String product = firstNonBlank(
                request == null ? null : request.productOrService(),
                snapshot.products().isEmpty() ? null : snapshot.products().getFirst(),
                "товар или услуга"
        );

        CompanyAiProfile profile = new CompanyAiProfile(
                buildDescription(snapshot, product),
                firstNonBlank(snapshot.subCategory(), snapshot.category(), "ниша компании"),
                snapshot.products(),
                snapshot.advantages(),
                snapshot.commonPositiveTopics(),
                snapshot.commonNegativeTopics(),
                buildFactualWarnings(snapshot)
        );

        int adCount = clamp(request == null ? null : request.adTextsCount(), 3, 20, 10);
        int postsCount = clamp(request == null ? null : request.socialPostsCount(), 3, 20, 10);
        int positiveReplyCount = clamp(request == null ? null : request.positiveReplyCount(), 3, 20, 10);
        int negativeReplyCount = clamp(request == null ? null : request.negativeReplyCount(), 3, 20, 10);
        List<String> utp = buildUtp(snapshot, product);
        List<String> postTopics = buildPostTopics(snapshot, product, postsCount);
        List<String> socialPosts = postTopics.stream()
                .map(topic -> buildArticlePost(snapshot, product, topic))
                .toList();

        return new ReputationContentPack(
                snapshot,
                profile,
                utp,
                buildAdTexts(snapshot, product, adCount),
                postTopics,
                socialPosts,
                buildHonestReviewTopics(snapshot, product, utp, postTopics),
                buildReviewDraftTemplates(snapshot, product, utp, socialPosts),
                buildPositiveReplies(snapshot, positiveReplyCount),
                buildNegativeReplies(snapshot, negativeReplyCount),
                snapshot.sources().stream().map(CompanySource::url).filter(url -> !url.isBlank()).distinct().toList(),
                buildSafetyNotes()
        );
    }

    private String buildDescription(ResearchSnapshot snapshot, String product) {
        String category = firstNonBlank(snapshot.subCategory(), snapshot.category(), "своей сфере");
        String advantages = snapshot.advantages().isEmpty()
                ? "сервисе, понятной коммуникации и проверенных фактах"
                : String.join(", ", snapshot.advantages().stream().limit(3).toList());
        String facts = evidenceFacts(snapshot).isEmpty()
                ? ""
                : " Из собранных источников можно использовать такие опорные факты: " + String.join("; ", evidenceFacts(snapshot).stream().limit(4).toList()) + ".";

        return String.format(
                "%s работает в сфере %s%s. Для продвижения %s стоит делать акцент на %s. Перед публикацией любые формулировки нужно сверить с реальным опытом клиентов.",
                firstNonBlank(snapshot.companyName(), "Компания"),
                category,
                locationPhrase(snapshot),
                product,
                advantages
        ) + facts;
    }

    private List<String> buildUtp(ResearchSnapshot snapshot, String product) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        List<String> facts = evidenceFacts(snapshot);
        for (String advantage : snapshot.advantages()) {
            result.add(capitalize(advantage) + " для клиентов, которые выбирают " + product + concreteSuffix(facts) + ".");
        }
        result.add("Понятный выбор перед покупкой: клиент видит формат, условия и реальные ориентиры" + concreteSuffix(facts) + ".");
        result.add("Коммуникация до оплаты: менеджер помогает уточнить сценарий, состав участников, возраст и ожидания.");
        result.add("Маркетинг строится на проверяемых фактах из карточек, сайта и отзывов, а не на пустых обещаниях.");
        return limit(result, 10);
    }

    private List<String> buildAdTexts(ResearchSnapshot snapshot, String product, int count) {
        String companyName = firstNonBlank(snapshot.companyName(), "Компания");
        String location = locationPhrase(snapshot);
        String anchor = concreteAnchor(snapshot);
        List<String> base = List.of(
                companyName + location + ": выберите " + product + " по фактам. Уточните формат, условия и детали заранее" + anchor + ".",
                "Нужен " + product + "? В " + companyName + " можно сравнить варианты, задать вопросы менеджеру и спокойно выбрать подходящий сценарий.",
                "Планируете " + product + "? Сначала проверьте адрес, возраст, состав участников, цены и отзывы, затем бронируйте без лишнего риска.",
                companyName + ": конкретные сценарии, понятные условия и помощь с выбором. Хороший вариант, когда хочется заранее понимать, что покупаешь.",
                "Выбирайте " + product + " не вслепую: смотрите рейтинг, отзывы, описание сценариев и ограничения. Менеджер поможет уточнить детали.",
                "Для семьи, компании или праздника важны формат и ожидания. " + companyName + " помогает подобрать " + product + " под вашу задачу.",
                "Перед заказом уточните количество участников, возраст, длительность и сложность. Так покупка становится спокойнее и предсказуемее.",
                "Ищете " + product + " в своем городе? Проверьте доступные сценарии, отзывы и условия, а затем выберите удобное время.",
                "Сравните варианты " + product + ": что входит, кому подходит, какие есть ограничения и что чаще отмечают гости в отзывах.",
                "Если важны эмоции и понятная организация, начните с консультации: расскажите состав команды и повод, а менеджер подскажет сценарий."
        );

        return repeatToCount(base, count);
    }

    private List<String> buildPostTopics(ResearchSnapshot snapshot, String product, int count) {
        List<String> base = new ArrayList<>();
        base.add("Как выбрать " + product + " под состав команды, возраст и повод");
        base.add("Что проверить перед бронированием: адрес, цены, ограничения и отзывы");
        base.add("Как читать отзывы о " + firstNonBlank(snapshot.companyName(), "компании") + " и отделять эмоции от фактов");
        base.add("Какие вопросы задать менеджеру перед заказом " + product);
        base.add("Разбор сценариев: кому подойдет спокойный формат, а кому нужна динамика");
        base.add("Как подготовиться к визиту и не потерять время на организационные мелочи");
        base.add("Почему рейтинг и количество отзывов помогают выбрать, но не заменяют консультацию");
        base.add("Что написать в честном отзыве после посещения, чтобы он был полезен другим клиентам");
        base.add("Как выбрать формат для дня рождения, компании друзей или семейного отдыха");
        base.add("Какие детали лучше уточнить заранее, если идут дети или большая компания");

        if (!snapshot.advantages().isEmpty()) {
            base.add("Почему важно: " + snapshot.advantages().getFirst());
        }

        return repeatToCount(base, count);
    }

    private List<String> buildSocialPosts(ResearchSnapshot snapshot, String product, int count) {
        List<String> topics = buildPostTopics(snapshot, product, count);
        return topics.stream()
                .map(topic -> buildArticlePost(snapshot, product, topic))
                .toList();
    }

    private List<String> buildHonestReviewTopics(ResearchSnapshot snapshot, String product, List<String> utp, List<String> postTopics) {
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        String companyName = firstNonBlank(snapshot.companyName(), "компании");
        topics.add("Выбор " + product + ": почему остановились на " + companyName + ", какую задачу решали и какой личный результат получили.");
        topics.add("УТП: " + firstNonBlank(firstItem(utp), "понятные условия") + " Личный штрих: что именно помогло вам принять решение.");
        topics.add("Сценарий из поста: " + firstNonBlank(firstItem(postTopics), "как выбрать подходящий формат") + ". Добавьте, с каким запросом пришли и что оказалось полезным.");
        topics.add("Польза услуги: какой конкретный товар, пакет или формат выбрали и почему он подошёл под вашу ситуацию.");
        topics.add("Доверие перед заказом: какие условия, цена, адрес, сроки, консультация или отзывы помогли не сомневаться.");
        topics.add("Организация процесса: как прошли запись, консультация, доставка, самовывоз, визит или сопровождение, если это было в вашем опыте.");
        for (String topic : snapshot.commonPositiveTopics()) {
            topics.add("Репутационный плюс: " + topic + ". Расскажите, как он проявился именно у вас.");
        }
        topics.add("Честное уточнение: что стоит проверить заранее и какой совет вы бы дали следующему клиенту.");
        return limit(topics, 10);
    }

    private List<String> buildReviewDraftTemplates(ResearchSnapshot snapshot, String product, List<String> utp, List<String> socialPosts) {
        String companyName = firstNonBlank(snapshot.companyName(), "компанию");
        String city = firstNonBlank(snapshot.city(), "городе");
        String anchor = concreteAnchor(snapshot);
        String utpAngle = firstNonBlank(firstItem(utp), "понятные условия и помощь с выбором");
        String postAngle = firstNonBlank(firstItem(socialPosts), "Перед выбором полезно сверить условия, формат и отзывы.");
        return List.of(
                "Выбирал(а) " + product + " в " + city + " и остановился(ась) на " + companyName + ". Для меня важным был не просто список услуг, а понятный выбор: " + shortSentence(utpAngle) + " Перед обращением смотрел(а) условия, отзывы и детали" + anchor + ". Личную часть лучше дописать через реальный опыт: что именно выбрали, как прошёл контакт и что подтвердилось.",
                "Обращался(лась) в " + companyName + " за " + product + ". Хорошо, когда компания заранее объясняет формат, условия и следующий шаг: " + shortSentence(postAngle) + " Это не заменяет личный опыт, но помогает идти без лишних ожиданий. В отзыве стоит добавить одну настоящую деталь визита или заказа и заранее уточнить цену, сроки, ограничения и состав услуги.",
                "Выбрали " + companyName + " после сравнения вариантов в " + city + ". Сработал конкретный плюс: " + shortSentence(utpAngle) + " По таким деталям легче понять, чем предложение отличается от похожих компаний. Личный пример лучше дописать отдельно: что выбрали, почему подошло и совпали ли ожидания с результатом.",
                "Опыт с " + companyName + " полезно описывать через сочетание услуги и понятной организации. " + shortSentence(postAngle) + " В личной части клиент добавляет свой повод, задачу или ограничение, а также 1-2 реальные детали, которые можно честно подтвердить после обращения."
        );
    }

    private List<String> buildPositiveReplies(ResearchSnapshot snapshot, int count) {
        String companyName = firstNonBlank(snapshot.companyName(), "нашу компанию");
        List<String> base = List.of(
                "Спасибо за отзыв! Рады, что вы остались довольны обращением в " + companyName + ". Будем рады видеть вас снова.",
                "Благодарим за теплые слова. Нам важно, что покупка прошла удобно и результат оправдал ожидания.",
                "Спасибо, что поделились впечатлениями. Передадим вашу обратную связь команде.",
                "Рады, что смогли помочь с выбором. Обращайтесь, если снова понадобится консультация.",
                "Спасибо за доверие и подробный отзыв. Для нас это важная обратная связь.",
                "Благодарим за оценку. Приятно знать, что вы отметили качество сервиса.",
                "Спасибо, что нашли время написать. Мы стараемся делать покупку понятной и удобной.",
                "Рады, что у вас остались хорошие впечатления. Будем рады помочь снова.",
                "Спасибо за обратную связь. Такие отзывы помогают нам понимать, что действительно важно клиентам.",
                "Благодарим за отзыв и доверие. Если понадобятся дополнительные рекомендации, обращайтесь."
        );

        return repeatToCount(base, count);
    }

    private List<String> buildNegativeReplies(ResearchSnapshot snapshot, int count) {
        String companyName = firstNonBlank(snapshot.companyName(), "компании");
        List<String> base = List.of(
                "Спасибо, что написали. Нам жаль, что опыт обращения в " + companyName + " оказался не таким, как ожидалось. Пожалуйста, напишите детали заказа, чтобы мы могли разобраться.",
                "Примем обратную связь в работу. Уточните, пожалуйста, дату обращения и детали ситуации, чтобы мы проверили информацию.",
                "Нам важно разобраться в ситуации. Если вы оставите контакт или номер заказа, менеджер сможет проверить детали и вернуться с ответом.",
                "Спасибо за сигнал. Мы проверим описанную ситуацию внутри команды и постараемся найти корректное решение.",
                "Сожалеем, что возникли сложности. Пожалуйста, уточните подробности, чтобы мы могли предметно помочь.",
                "Спасибо за обратную связь. Мы не хотим оставлять такую ситуацию без внимания и готовы проверить детали.",
                "Нам жаль, что впечатление оказалось негативным. Напишите, пожалуйста, когда было обращение и что именно пошло не так.",
                "Понимаем ваше недовольство. Чтобы разобраться корректно, нам нужны детали покупки или обращения.",
                "Спасибо, что сообщили. Передадим информацию ответственным и вернемся с ответом после проверки.",
                "Сожалеем о таком опыте. Пожалуйста, свяжитесь с нами, чтобы мы смогли предметно решить вопрос."
        );

        return repeatToCount(base, count);
    }

    private List<String> buildFactualWarnings(ResearchSnapshot snapshot) {
        List<String> warnings = new ArrayList<>();
        warnings.addAll(snapshot.warnings());
        if (!snapshot.searchAvailable()) {
            warnings.add("Публичный поиск не был доступен: пакет основан в основном на данных CRM и ручных подсказках.");
        } else if (snapshot.searchResultsCount() == 0) {
            warnings.add("Публичный поиск не дал релевантных результатов: добавьте сайт, карточку или справочник вручную.");
        }
        if (snapshot.sources().stream().noneMatch(source -> "website".equals(source.type()))) {
            warnings.add("Сайт или публичные страницы не были прочитаны: часть фактов нужно проверить вручную.");
        }
        if (snapshot.products().isEmpty()) {
            warnings.add("Не найдены конкретные товары или услуги: лучше добавить их вручную перед генерацией.");
        }
        return warnings;
    }

    private List<String> buildSafetyNotes() {
        return List.of(
                "Не публикуйте черновики как отзывы от имени несуществующих клиентов.",
                "Черновик отзыва должен редактировать реальный клиент на основе своего опыта.",
                "Не добавляйте факты, которых не было в покупке или обращении.",
                "Кнопку в интерфейсе лучше называть 'Скопировать черновик', а не 'Опубликовать отзыв'."
        );
    }

    private String buildArticlePost(ResearchSnapshot snapshot, String product, String topic) {
        String companyName = firstNonBlank(snapshot.companyName(), "компания");
        String city = firstNonBlank(snapshot.city(), "вашем городе");
        String category = firstNonBlank(snapshot.subCategory(), snapshot.category(), product);
        List<String> facts = evidenceFacts(snapshot);
        String factLine = facts.isEmpty()
                ? "Перед выбором стоит сверить описание, условия, отзывы и задать вопросы менеджеру."
                : "Из открытых источников для ориентира можно использовать такие факты: " + String.join("; ", facts.stream().limit(4).toList()) + ".";
        String positiveTopics = snapshot.commonPositiveTopics().isEmpty()
                ? "понятные условия, атмосфера, организация и соответствие ожиданиям"
                : String.join(", ", snapshot.commonPositiveTopics().stream().limit(4).toList());

        return topic + "\n\n"
                + "Когда клиент выбирает " + product + " в " + city + ", ему важно не просто увидеть красивое описание, а понять, подходит ли формат под его задачу. Для " + companyName + " это особенно важно: ниша \"" + category + "\" держится на ожиданиях, эмоциях и точном совпадении сценария с составом гостей.\n\n"
                + factLine + "\n\n"
                + "Практичный порядок выбора простой: сначала определите повод и состав участников, затем уточните ограничения по возрасту, длительность, сложность, свободное время и итоговую стоимость. После этого полезно посмотреть, что люди чаще отмечают в отзывах: " + positiveTopics + ". Такие детали помогают снизить риск разочарования и выбрать вариант без лишней суеты.\n\n"
                + "Перед бронированием лучше задать менеджеру 3 вопроса: кому подойдет выбранный сценарий, что входит в цену и какие нюансы стоит знать заранее. Так ожидания клиента становятся конкретнее, а итоговый опыт чаще совпадает с тем, что человек хотел получить.\n\n"
                + "Если вы уже были в " + companyName + ", оставьте честный отзыв: что выбрали, с кем ходили, что понравилось и что можно улучшить. Такой отзыв полезен будущим клиентам и помогает компании точнее развивать сервис.";
    }

    private List<String> evidenceFacts(ResearchSnapshot snapshot) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (CompanySource source : snapshot.sources()) {
            if (isLowTrustFactSource(source)) {
                continue;
            }
            collectFacts(result, source.title());
            collectFacts(result, source.excerpt());
            if (!source.url().isBlank() && !"database".equals(source.type()) && !"manual".equals(source.type())) {
                result.add("источник: " + source.url());
            }
        }

        return result.stream().limit(12).toList();
    }

    private boolean isLowTrustFactSource(CompanySource source) {
        String type = source.type();
        return type.contains("catalog_listing")
                || type.contains("competitor_listing")
                || type.contains("unknown_public");
    }

    private void collectFacts(LinkedHashSet<String> result, String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        String normalized = text.replaceAll("\\s+", " ").trim();
        Matcher matcher = FACT_PATTERN.matcher(normalized);
        while (matcher.find() && result.size() < 16) {
            String fact = matcher.group().trim();
            if (fact.length() >= 4 && fact.length() <= 160) {
                result.add(fact);
            }
        }
    }

    private String locationPhrase(ResearchSnapshot snapshot) {
        String city = firstNonBlank(snapshot.city());
        return city.isBlank() ? "" : " в городе " + city;
    }

    private String concreteAnchor(ResearchSnapshot snapshot) {
        List<String> facts = evidenceFacts(snapshot);
        return facts.isEmpty() ? "" : " (" + facts.getFirst() + ")";
    }

    private String concreteSuffix(List<String> facts) {
        return facts.isEmpty() ? "" : ": " + facts.getFirst().toLowerCase(Locale.ROOT);
    }

    private List<String> repeatToCount(List<String> base, int count) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String text = base.get(i % base.size());
            if (i >= base.size()) {
                text = text + " Дополнительный акцент: адаптируйте формулировку под конкретный продукт и аудиторию.";
            }
            result.add(text);
        }
        return result;
    }

    private int clamp(Integer value, int min, int max, int fallback) {
        int actual = value == null ? fallback : value;
        return Math.max(min, Math.min(max, actual));
    }

    private List<String> limit(LinkedHashSet<String> values, int limit) {
        return values.stream().limit(limit).toList();
    }

    private String firstItem(List<String> values) {
        return values == null || values.isEmpty() ? "" : values.getFirst();
    }

    private String shortSentence(String value) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (clean.isBlank()) {
            return "";
        }
        int sentenceEnd = clean.indexOf('.');
        if (sentenceEnd > 40 && sentenceEnd <= 180) {
            return clean.substring(0, sentenceEnd + 1);
        }
        return clean.length() <= 180 ? clean : clean.substring(0, 177).trim() + "...";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return "";
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return value.substring(0, 1).toUpperCase() + value.substring(1);
    }
}
