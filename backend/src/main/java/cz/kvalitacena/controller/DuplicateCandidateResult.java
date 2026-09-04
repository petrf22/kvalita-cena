package cz.kvalitacena.controller;

import java.util.List;

public record DuplicateCandidateResult(List<DuplicateCandidate> items, int totalCount, boolean hasMore) {
}
