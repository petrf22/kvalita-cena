package cz.kvalitacena.db.entity;

public enum RevokeReason {
  ROTATED,
  REUSE_DETECTED,
  LOGOUT,
  USER_REVOKED,
  EXPIRED,
  /** Moderátor pozastavil účet (docs/podminky-uziti.md, "Ukončení a vyloučení") — viz ModerationService.setUserSuspended. */
  SUSPENDED
}
