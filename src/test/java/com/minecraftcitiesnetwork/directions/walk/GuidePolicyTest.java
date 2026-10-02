package com.minecraftcitiesnetwork.directions.walk;

import com.minecraftcitiesnetwork.directions.walk.GuidePolicy.Observation;
import com.minecraftcitiesnetwork.directions.walk.GuidePolicy.Reason;
import com.minecraftcitiesnetwork.directions.walk.GuidePolicy.SearchOrder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuidePolicyTest {
    private static final GuidePolicy.Tuning TUNING = new GuidePolicy.Tuning(48, 6, 2000);

    private static Route straight(int fromX, int toX, boolean complete, double endEstimate) {
        List<Cell> cells = new ArrayList<>();
        for (int x = fromX; x <= toX; x++) {
            cells.add(new Cell(x, 65, 0));
        }
        return Route.of(cells, complete, endEstimate);
    }

    private static SearchResult found(Route route) {
        return new SearchResult(route.complete() ? SearchResult.Status.COMPLETE : SearchResult.Status.PARTIAL,
                route, route.length(), 10, 1000);
    }

    private static SearchResult noRoute() {
        return SearchResult.failed(SearchResult.Status.NO_ROUTE, 10, 1000);
    }

    private static Observation at(long now, double x) {
        return new Observation(now, x + 0.5, 65, 0.5, true, false);
    }

    private static Observation at(long now, double x, boolean onFoot) {
        return new Observation(now, x + 0.5, 65, 0.5, onFoot, false);
    }

    /** A policy following {@code route}, with its one search issued and answered at time 0. */
    private static GuidePolicy following(Route route) {
        GuidePolicy p = new GuidePolicy(TUNING);
        SearchOrder first = p.tick(at(0, 0));
        p.onResult(first.generation(), found(route), 0);
        return p;
    }

    @Test
    void firstTickOrdersTheInitialSearch() {
        SearchOrder order = new GuidePolicy(TUNING).tick(at(0, 0));
        assertNotNull(order);
        assertEquals(Reason.INITIAL, order.reason());
        assertEquals(0, order.generation());
        assertTrue(order.spentExits().isEmpty());
    }

    @Test
    void onlyOneSearchIsInFlight() {
        GuidePolicy p = new GuidePolicy(TUNING);
        p.tick(at(0, 0));
        assertNull(p.tick(at(5000, 0)));
    }

    @Test
    void completeRouteIsFollowedWithoutReplanning() {
        GuidePolicy p = following(straight(0, 100, true, 0));
        assertNull(p.tick(at(5000, 10)));
        assertNotNull(p.route());
        assertEquals(10, p.cursor());
    }

    @Test
    void strayingReplansAfterTheCooldown() {
        GuidePolicy p = following(straight(0, 100, true, 0));
        assertNull(p.tick(new Observation(1000, 10.5, 65, 10.5, true, false)));
        SearchOrder order = p.tick(new Observation(2500, 10.5, 65, 10.5, true, false));
        assertNotNull(order);
        assertEquals(Reason.OFF_ROUTE, order.reason());
    }

    @Test
    void partialRouteIsExtendedNearItsEndAndItsExitIsSpent() {
        GuidePolicy p = following(straight(0, 100, false, 50));
        assertNull(p.tick(at(3000, 40)));
        SearchOrder order = p.tick(at(3000, 60));
        assertNotNull(order);
        assertEquals(Reason.EXTEND, order.reason());
        assertEquals(List.of(new Cell(100, 65, 0)), order.spentExits());
    }

    @Test
    void blockedRouteReplansWithoutWaitingForTheCooldown() {
        GuidePolicy p = following(straight(0, 100, true, 0));
        assertNull(p.tick(new Observation(100, 5.5, 65, 0.5, true, true)));
        SearchOrder order = p.tick(new Observation(300, 5.5, 65, 0.5, true, true));
        assertNotNull(order);
        assertEquals(Reason.BLOCKED, order.reason());
    }

    @Test
    void notOnFootSuppressesStrayingButNotBlocking() {
        GuidePolicy p = following(straight(0, 100, true, 0));
        assertNull(p.tick(new Observation(5000, 10.5, 70, 10.5, false, false)));
        SearchOrder order = p.tick(new Observation(5000, 10.5, 70, 10.5, false, true));
        assertNotNull(order);
        assertEquals(Reason.BLOCKED, order.reason());
    }

    @Test
    void failureIsAnnouncedOnceAndRetried() {
        GuidePolicy p = new GuidePolicy(TUNING);
        SearchOrder first = p.tick(at(0, 0));
        p.onResult(first.generation(), noRoute(), 0);
        assertTrue(p.failed());
        assertNull(p.route());
        assertTrue(p.takeNoRouteMessage());
        assertFalse(p.takeNoRouteMessage());

        assertNull(p.tick(at(5000, 0)));
        SearchOrder retry = p.tick(at(10_000, 0));
        assertNotNull(retry);
        assertEquals(Reason.RETRY, retry.reason());
        p.onResult(retry.generation(), noRoute(), 10_000);
        assertFalse(p.takeNoRouteMessage());

        SearchOrder moved = p.tick(at(11_000, 20));
        assertNotNull(moved);
        assertEquals(Reason.RETRY, moved.reason());
    }

    @Test
    void failedExtensionKeepsTheCurrentRoute() {
        GuidePolicy p = following(straight(0, 100, false, 50));
        SearchOrder order = p.tick(at(3000, 60));
        p.onResult(order.generation(), noRoute(), 3000);
        assertNotNull(p.route());
        assertFalse(p.failed());
    }

    @Test
    void teleportFarFromTheRouteStartsAgain() {
        GuidePolicy p = following(straight(0, 100, true, 0));
        SearchOrder order = p.tick(at(5000, 500));
        assertNotNull(order);
        assertEquals(Reason.INITIAL, order.reason());
        assertEquals(1, order.generation());
        assertNull(p.route());
    }

    @Test
    void resultOfAnEarlierGenerationIsIgnored() {
        GuidePolicy p = new GuidePolicy(TUNING);
        SearchOrder old = p.tick(at(0, 0));
        p.reset();
        assertNull(p.tick(at(500, 0)));
        p.onResult(old.generation(), found(straight(0, 10, true, 0)), 500);
        assertNull(p.route());
        SearchOrder fresh = p.tick(at(1000, 0));
        assertNotNull(fresh);
        assertEquals(1, fresh.generation());
    }

    @Test
    void resetForgetsSpentExits() {
        GuidePolicy p = following(straight(0, 100, false, 50));
        SearchOrder extend = p.tick(at(3000, 60));
        p.onResult(extend.generation(), noRoute(), 3000);
        p.reset();
        SearchOrder order = p.tick(at(4000, 60));
        assertNotNull(order);
        assertTrue(order.spentExits().isEmpty());
    }

    @Test
    void notOnFootSuppressesInitialSearch() {
        GuidePolicy p = new GuidePolicy(TUNING);
        assertNull(p.tick(at(0, 0, false)));
        SearchOrder order = p.tick(at(0, 0, true));
        assertNotNull(order);
        assertEquals(Reason.INITIAL, order.reason());
    }

    @Test
    void notOnFootSuppressesRetrySearch() {
        GuidePolicy p = new GuidePolicy(TUNING);
        SearchOrder first = p.tick(at(0, 0));
        p.onResult(first.generation(), noRoute(), 0);
        assertTrue(p.failed());

        assertNull(p.tick(at(10_000, 0, false)));
        SearchOrder retry = p.tick(at(10_000, 0, true));
        assertNotNull(retry);
        assertEquals(Reason.RETRY, retry.reason());
    }

    @Test
    void duplicateSpentExitsAreNotAdded() {
        GuidePolicy p = following(straight(0, 100, false, 50));
        SearchOrder first = p.tick(at(3000, 60));
        p.onResult(first.generation(), noRoute(), 3000);
        assertEquals(List.of(new Cell(100, 65, 0)), first.spentExits());

        SearchOrder second = p.tick(at(5000, 60));
        assertNotNull(second);
        assertEquals(Reason.EXTEND, second.reason());
        assertEquals(List.of(new Cell(100, 65, 0)), second.spentExits());
        assertEquals(1, second.spentExits().size());
    }

    @Test
    void uncapturedStartWaitsQuietlyThenSearchesAgain() {
        GuidePolicy p = new GuidePolicy(TUNING);
        SearchOrder first = p.tick(at(0, 0));
        p.onResult(first.generation(), SearchResult.failed(SearchResult.Status.UNCAPTURED_START, 0, 1000), 0);
        assertFalse(p.failed());
        assertFalse(p.takeNoRouteMessage());
        assertNull(p.route());
        assertNull(p.tick(at(500, 0)));
        SearchOrder again = p.tick(at(1000, 0));
        assertNotNull(again);
        assertEquals(Reason.INITIAL, again.reason());
    }
}
