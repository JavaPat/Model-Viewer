package com.modelviewer.search;

import com.modelviewer.database.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes cross-table asset searches against the SQLite index.
 *
 * <p>Two search strategies are applied depending on input:
 * <ul>
 *   <li>If the query is a pure integer → search all tables by exact ID</li>
 *   <li>Otherwise → LIKE '%query%' search on name columns (NPCs, items,
 *       objects) plus ID prefix search on models and animations</li>
 * </ul>
 *
 * Results are grouped by asset type (NPCs first, then items, objects,
 * models, animations) and capped at {@code limitPerCategory} per type.
 */
public final class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /** Default maximum results returned per asset category. */
    public static final int DEFAULT_LIMIT = 20;

    private final NpcRepository       npcs;
    private final ItemRepository      items;
    private final ObjectRepository    objects;
    private final ModelRepository     models;
    private final AnimationRepository animations;

    public SearchService(DatabaseManager db) {
        this.npcs       = new NpcRepository(db);
        this.items      = new ItemRepository(db);
        this.objects    = new ObjectRepository(db);
        this.models     = new ModelRepository(db);
        this.animations = new AnimationRepository(db);
    }

    /**
     * Searches all asset tables for the given query string.
     *
     * @param query            text to search (ID number or name fragment)
     * @param limitPerCategory maximum results per category
     * @return flat list of results grouped by asset type, in display order
     */
    public List<SearchResult> search(String query, int limitPerCategory) {
        if (query == null) return List.of();
        String trimmed = query.trim();
        if (trimmed.isEmpty()) return List.of();

        List<SearchResult> results = new ArrayList<>();

        boolean isNumeric = trimmed.matches("\\d+");
        if (isNumeric) {
            searchById(trimmed, Integer.parseInt(trimmed), limitPerCategory, results);
        } else {
            searchByName(trimmed, limitPerCategory, results);
        }

        return results;
    }

    /** Overload using the default limit. */
    public List<SearchResult> search(String query) {
        return search(query, DEFAULT_LIMIT);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void searchById(String prefix, int exactId, int limit,
                            List<SearchResult> out) {
        // NPCs by exact ID
        NpcRepository.NpcRecord npc = npcs.findById(exactId);
        if (npc != null) {
            out.add(new SearchResult(SearchResult.AssetType.NPC, npc.id(), npc.toString()));
        }

        // Items by exact ID
        ItemRepository.ItemRecord item = items.findById(exactId);
        if (item != null) {
            out.add(new SearchResult(SearchResult.AssetType.ITEM, item.id(), item.toString()));
        }

        // Objects by exact ID
        ObjectRepository.ObjectRecord obj = objects.findById(exactId);
        if (obj != null) {
            out.add(new SearchResult(SearchResult.AssetType.OBJECT, obj.id(), obj.toString()));
        }

        // Models — also include prefix matches
        for (ModelRepository.ModelRecord m : models.searchById(prefix, limit)) {
            out.add(new SearchResult(SearchResult.AssetType.MODEL, m.id(), m.toString()));
        }

        // Animations by exact ID
        AnimationRepository.AnimationRecord anim = animations.findById(exactId);
        if (anim != null) {
            out.add(new SearchResult(SearchResult.AssetType.ANIMATION, anim.id(), anim.toString()));
        }
    }

    private void searchByName(String query, int limit, List<SearchResult> out) {
        for (NpcRepository.NpcRecord r : npcs.searchByName(query, limit)) {
            out.add(new SearchResult(SearchResult.AssetType.NPC, r.id(), r.toString()));
        }
        for (ItemRepository.ItemRecord r : items.searchByName(query, limit)) {
            out.add(new SearchResult(SearchResult.AssetType.ITEM, r.id(), r.toString()));
        }
        for (ObjectRepository.ObjectRecord r : objects.searchByName(query, limit)) {
            out.add(new SearchResult(SearchResult.AssetType.OBJECT, r.id(), r.toString()));
        }
        // Models have no name — skip in name search
        // Animations have no name — skip in name search
    }
}
