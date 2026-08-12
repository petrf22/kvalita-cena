package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.ProfileVisibility;

import java.util.List;

/**
 * Patch nad {@code auth.user_profile} (+ {@code app_user.display_name}) — {@code null} u pole
 * znamená "nezměněno", {@code clearX = true} maže hodnotu (stejný vzor jako
 * {@link UpdateStoreInput}/{@link UpdateProductInput}, ne "prázdný řetězec = smazat").
 *
 * <p>{@code visibleFields}: {@code null} nechá dosavadní matici beze změny, JAKÝKOLI seznam
 * (i prázdný) ji celou nahradí — je to náhrada, ne patch po řádcích.
 */
public record UpdateProfileInput(
    String firstName,
    Boolean clearFirstName,
    String lastName,
    Boolean clearLastName,
    String displayName,
    Boolean clearDisplayName,
    String phone,
    Boolean clearPhone,
    String contactEmail,
    Boolean clearContactEmail,
    ProfileVisibility visibility,
    List<ProfileFieldAudience> visibleFields) {
}
