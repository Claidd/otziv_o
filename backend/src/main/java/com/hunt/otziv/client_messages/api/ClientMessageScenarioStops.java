package com.hunt.otziv.client_messages.api;

/**
 * Why the autoresponder does not run a scenario right now, in the words of the settings page («выключен переключатель
 * «…»», «автоответчик на паузе…»), or null when it runs. Read fresh from the settings; unavailable settings count as
 * stopped. Manager control uses it so a card never calls a switched-off scenario a fault.
 */
public interface ClientMessageScenarioStops {

    /** @param scenario a {@code ClientMessageScenario} name, e.g. "CLIENT_TEXT_REMINDER"; unknown names never stop */
    String stopReason(String scenario);
}
