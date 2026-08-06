package cz.kvalitacena.controller;

import java.util.List;

public record GeocodeResult(List<GeocodeCandidate> candidates, String attribution) {
}
