package com.hunt.otziv.whatsapp.api;

/** Caller supplies the persisted business occurrence; this boundary freezes its first envelope. */
public interface WhatsAppBusinessOperations {
    FrozenMessage freeze(String operationId,String clientId,String kind,String destination,String message);
    FrozenMessage freezeForDispatch(String operationId,String clientId,String kind,String destination,String message);
    java.util.Optional<FrozenMessage> findFrozen(String operationId);
    FrozenMessage requireFrozen(String operationId);
    void requireMatches(String operationId,String clientId,String kind,String destination,String message);
    String createManualOperation(String actor);
    void requireManualOwner(String operationId,String actor);

    record FrozenMessage(String operationId,String clientId,String kind,String destination,String message) {
        @Override public String toString() {return "FrozenMessage[operationId="+operationId+",kind="+kind+"]";}
    }
}
