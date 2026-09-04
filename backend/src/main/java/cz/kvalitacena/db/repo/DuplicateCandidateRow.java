package cz.kvalitacena.db.repo;

/** Jedna dvojice podezřele podobných lokálních položek — projekce nativního dotazu. */
public interface DuplicateCandidateRow {
  Long getLeftId();

  Long getRightId();

  double getScore();
}
