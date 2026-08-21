package com.elma.gohan.application;

import com.elma.gohan.domain.recommendation.FlavorTag;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.infrastructure.persistence.RestaurantEntity;
import com.elma.gohan.infrastructure.persistence.RestaurantFlavorObservationEntity;
import com.elma.gohan.infrastructure.persistence.RestaurantFlavorObservationRepository;
import com.elma.gohan.infrastructure.persistence.RestaurantRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FlavorFeatureService {
    private final RestaurantRepository restaurantRepository;
    private final RestaurantFlavorObservationRepository observationRepository;

    public FlavorFeatureService(RestaurantRepository restaurantRepository,
            RestaurantFlavorObservationRepository observationRepository) {
        this.restaurantRepository = restaurantRepository;
        this.observationRepository = observationRepository;
    }

    public Map<String, Set<FlavorTag>> loadForCandidates(UUID userId,
            Collection<Restaurant> restaurants) {
        Map<String, List<String>> idsBySource = new HashMap<>();
        restaurants.forEach(r -> idsBySource.computeIfAbsent(r.source(), ignored -> new ArrayList<>())
                .add(r.sourcePoiId()));
        Map<UUID, String> keys = new HashMap<>();
        idsBySource.forEach((source, ids) -> restaurantRepository
                .findBySourceAndSourcePoiIdIn(source, ids)
                .forEach(entity -> keys.put(entity.getId(), key(entity.getSource(), entity.getSourcePoiId()))));
        if (keys.isEmpty()) return Map.of();
        List<RestaurantFlavorObservationEntity> observations =
                observationRepository.findByRestaurantIdIn(keys.keySet());
        Map<String, Map<FlavorTag, Set<UUID>>> publicUsers = new HashMap<>();
        Map<String, Set<FlavorTag>> result = new HashMap<>();
        for (var observation : observations) {
            String key = keys.get(observation.getRestaurantId());
            FlavorTag tag = FlavorTag.valueOf(observation.getFlavorTag());
            publicUsers.computeIfAbsent(key, ignored -> new HashMap<>())
                    .computeIfAbsent(tag, ignored -> new HashSet<>())
                    .add(observation.getAnonymousUserId());
            if (observation.getAnonymousUserId().equals(userId)) {
                result.computeIfAbsent(key, ignored -> new HashSet<>()).add(tag);
            }
        }
        publicUsers.forEach((restaurantKey, tags) -> tags.forEach((tag, users) -> {
            if (users.size() >= 2) result.computeIfAbsent(restaurantKey, ignored -> new HashSet<>()).add(tag);
        }));
        return result;
    }

    public Set<FlavorTag> loadForRestaurant(UUID userId, UUID restaurantId) {
        List<RestaurantFlavorObservationEntity> observations =
                observationRepository.findByRestaurantIdIn(List.of(restaurantId));
        Set<FlavorTag> result = new HashSet<>();
        Map<FlavorTag, Set<UUID>> users = new HashMap<>();
        for (var observation : observations) {
            FlavorTag tag = FlavorTag.valueOf(observation.getFlavorTag());
            users.computeIfAbsent(tag, ignored -> new HashSet<>()).add(observation.getAnonymousUserId());
            if (observation.getAnonymousUserId().equals(userId)) result.add(tag);
        }
        users.forEach((tag, distinct) -> { if (distinct.size() >= 2) result.add(tag); });
        return result;
    }

    public void record(UUID userId, RestaurantEntity restaurant, Collection<FlavorTag> tags,
            String result, LocalDateTime observedAt) {
        if (tags == null) return;
        tags.forEach(tag -> observationRepository.save(new RestaurantFlavorObservationEntity(
                restaurant.getId(), userId, tag.name(), result, observedAt)));
    }

    public static String key(Restaurant restaurant) { return key(restaurant.source(), restaurant.sourcePoiId()); }
    private static String key(String source, String sourcePoiId) { return source + ":" + sourcePoiId; }
}
