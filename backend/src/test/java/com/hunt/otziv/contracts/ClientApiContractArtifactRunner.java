package com.hunt.otziv.contracts;

/** Optional offline artifact check. Put the verified BOOT-INF/classes and libs
 * before test dependencies on the classpath; no Spring application is started. */
public final class ClientApiContractArtifactRunner {
    private ClientApiContractArtifactRunner() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected isolated archived source root");
        System.setProperty("client.contract.root", args[0]);
        System.setProperty("client.contract.export", "true");
        new ClientApiContractExportTest().compiledSpringMappingsAndMvcJacksonPropertiesMatchCheckedInContract();
        new ClientApiJacksonFixtureTest().everyGeneratedDtoRoundTripsThroughActualJacksonAndMatchesItsWireShape();
        System.out.println("Published artifact Spring/Jackson contract and DTO serialization PASS");
    }
}
