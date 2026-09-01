package cz.kvalitacena.controller;

/** GraphQL projekce {@link cz.kvalitacena.security.FeedbackChallengeService.IssuedChallenge}. */
public record FeedbackChallenge(String token, String salt, int difficulty) {
}
