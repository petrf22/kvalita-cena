package cz.kvalitacena.controller;

import java.util.List;

public record OsmStoreSearchResult(List<OsmStoreCandidate> candidates, String attribution, boolean available) {
}
