package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Store;

import java.util.List;

public record SearchFacets(List<Store> stores, List<String> cities) {
}
