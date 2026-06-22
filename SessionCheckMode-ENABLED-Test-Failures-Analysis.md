# SessionCheckMode.ENABLED Test Failures - Detailed Analysis

## Overview
After changing SessionCheckMode default from DISABLED to ENABLED in Hibernate 8.0, 7 tests fail. This document provides detailed analysis of each failure.

---

## Test Failure #1 & #2: CacheModeRefreshSessionTest

### Tests:
1. `refreshSessionRefreshesManagedFindMultipleResults` (line 164)
2. `refreshSessionRefreshesManagedMultiLoadResults` (line 193)

### Expected Behavior:
```java
// Load entities into session
final var first = session.find(RefreshSessionItem.class, 6L);
final var second = session.find(RefreshSessionItem.class, 7L);

// Update in database (outside session)
updateItem(session, 6L, "updated one");
updateItem(session, 7L, "updated two");

// Load with CacheMode.REFRESH_SESSION - should reload from DB
final var refreshResults = session.byMultipleIds(RefreshSessionItem.class)
    .with(CacheMode.REFRESH_SESSION)
    .multiLoad(6L, 7L);

// EXPECTS: first.name == "updated one" (refreshed from DB)
// EXPECTS: first.version == 1 (incremented)
```

### Actual Behavior with ENABLED:
- `loadFromCaches()` checks session first when SessionCheckMode.ENABLED
- Finds entities in session (they're managed)
- Returns cached entities WITHOUT refreshing from database
- Entities still have old values: `first.name == "one"`, `first.version == 0`

### Root Cause:
**CacheMode.REFRESH_SESSION is not respected when SessionCheckMode.ENABLED**

The `loadFromCaches()` method in `AbstractMultiIdEntityLoader.java` returns the session-cached entity immediately when `entry.isManaged()` is true, without checking if `CacheMode.REFRESH_SESSION` was specified.

### Solution Options:
1. **Check CacheMode before returning cached entity:**
   ```java
   if (sessionEntity != null && entry.isManaged()) {
       // Check if refresh is requested
       if (loadOptions.getCacheMode() == CacheMode.REFRESH_SESSION) {
           // Add to unresolved IDs to force DB reload
           unresolvedIds.add(id);
           return unresolvedIds;
       }
       // Otherwise return cached entity
       resolutionConsumer.consume(i, entityKey, (R) sessionEntity);
       return unresolvedIds;
   }
   ```

2. **Document as breaking change:** CacheMode.REFRESH_SESSION requires explicit SessionCheckMode.DISABLED in Hibernate 8.0

---

## Test Failure #3: MultiLoadTest.testUnorderedMultiLoadFrom2ndLevelCachePendingDelete

### Test Location: Line 580

### Expected Behavior:
```java
// Remove entity from session (mark for deletion)
session.remove(session.find(SimpleEntity.class, 2));

// Load 3 entities in unordered mode with ENABLED
final List<SimpleEntity> entities = session.findMultiple(
    SimpleEntity.class, 
    ids(3),
    CacheMode.NORMAL,
    FindMultipleOption.SessionCheckMode.ENABLED,
    FindMultipleOption.OrderingMode.UNORDERED
);

// EXPECTS: 3 items with one null (for deleted entity #2)
assertEquals(3, entities.size());
assertTrue(entities.stream().anyMatch(Objects::isNull));
```

### Actual Behavior with ENABLED:
- Test explicitly uses `SessionCheckMode.ENABLED` and default `RemovalsMode.REPLACE`
- My implementation added INCLUDE case handling which returns the deleted entity
- The null filtering logic removes unresolved nulls but keeps REPLACE mode nulls
- However, the deleted entity is being returned instead of null

### Root Cause:
**INCLUDE case was added but default RemovalsMode is REPLACE**

The test expects REPLACE behavior (null for deleted entity), but the implementation may be incorrectly handling the deleted entity case.

### Solution:
Review the `loadFromCaches()` logic for REPLACE mode - it should call `resolutionConsumer.consume(i, entityKey, null)` for deleted entities, and the null should be preserved in the results.

---

## Test Failure #4 & #5: MultiLoadLockingTest

### Tests:
1. `testMultiLoadCompositeIdEntityPessimisticReadLockAlreadyInSession` (line 368)
2. `testMultiLoadSimpleIdEntityPessimisticWriteLockSomeInL1CAndSomeInL2C` (line 368)

### Expected Behavior:
```java
// Load entity into session without lock
EntityWithAggregateId entityInL1C = session.find(
    EntityWithAggregateId.class, 
    entityWithAggregateIdList.get(0).getKey()
);

// Multi-load with PESSIMISTIC_READ lock
List<EntityWithAggregateId> entitiesLoaded = session.byMultipleIds(EntityWithAggregateId.class)
    .with(LockMode.PESSIMISTIC_READ)
    .multiLoad(entityWithAggregateIdKeys);

// EXPECTS: Lock upgraded to PESSIMISTIC_READ
// EXPECTS: SQL statement with "FOR UPDATE" clause
entitiesLoaded.forEach(entity -> 
    assertEquals(LockMode.PESSIMISTIC_READ, session.getCurrentLockMode(entity))
);
checkStatement(1, lockString); // Expects 1 SQL with lock
```

### Actual Behavior with ENABLED:
- Entity found in session (managed)
- Returned from cache without lock upgrade
- No SQL statement executed (checkStatement fails - expects 1, got 0)
- Lock mode remains NONE instead of PESSIMISTIC_READ

### Root Cause:
**Lock upgrade not triggered when entity returned from session cache**

The `loadFromSessionCache()` helper calls `upgradeLock()` internally, but this only works when the entity is being loaded/initialized. When SessionCheckMode.ENABLED returns the cached entity directly via `resolutionConsumer.consume()`, the lock upgrade never happens.

### Solution Options:
1. **Force reload when lock requested:**
   ```java
   if (sessionEntity != null && entry.isManaged() 
           && lockOptions.getLockMode() != LockMode.NONE) {
       // Add to unresolved to force reload with lock
       unresolvedIds.add(id);
       return unresolvedIds;
   }
   ```

2. **Upgrade lock before returning cached entity:**
   ```java
   if (sessionEntity != null && entry.isManaged()) {
       // Upgrade lock if needed
       if (lockOptions.getLockMode() != LockMode.NONE) {
           upgradeLock(sessionEntity, entry, lockOptions, session);
       }
       resolutionConsumer.consume(i, entityKey, (R) sessionEntity);
       return unresolvedIds;
   }
   ```

---

## Test Failure #6: FindMultipleDocTests.testReplaceRemovals

### Test Location: Line 112

### Expected Behavior:
```java
// Remove entity #5
session.remove(session.find(Person.class, 5));

// Load with REPLACE mode (default)
List<Person> persons = session.findMultiple(
    Person.class,
    List.of(1,2,3,4,5),
    FindMultipleOption.SessionCheckMode.ENABLED,
    FindMultipleOption.RemovalsMode.REPLACE,
    FindMultipleOption.OrderingMode.UNORDERED
);

// EXPECTS: 5 items with one null (for deleted entity #5)
assertThat(persons).hasSize(5);
assertThat(persons).containsNull();
```

### Actual Behavior:
- Test fails at `assertThat(persons).hasSize(5)` 
- Likely getting 4 items instead of 5
- The null for deleted entity is being filtered out

### Root Cause:
**Null filtering in unordered mode removes REPLACE mode nulls**

The implementation in `unorderedMultiLoad()` filters nulls:
```java
// Remove only nulls that were never resolved (non-existent IDs)
for (int i = results.size() - 1; i >= 0; i--) {
    if (results.get(i) == null && !resolvedPositions.contains(i)) {
        results.remove(i);
    }
}
```

However, REPLACE mode nulls ARE added to `resolvedPositions` (via `resolutionConsumer.consume(i, entityKey, null)`), so they should be kept. The issue is that the null filtering logic is removing them anyway.

### Solution:
The logic should work correctly if `resolvedPositions.add(position)` is called in the consumer when null is passed. Need to verify the consumer is being called correctly for REPLACE mode.

---

## Test Failure #7: FindMultipleDocTests.testExcludeRemovalsUnorderedNoSessionCheck

### Test Location: Line 180
### Annotation: `@FailureExpected(reason = "EXCLUDE has no effect with DISABLED at least for now")`

### Expected Behavior:
This test is marked with `@FailureExpected` - it's SUPPOSED to fail when SessionCheckMode is DISABLED (the old default).

### Actual Behavior with ENABLED:
- Test now PASSES because SessionCheckMode.ENABLED is the new default
- The test doesn't explicitly specify SessionCheckMode, so it gets ENABLED
- With ENABLED, EXCLUDE mode works correctly
- The `@FailureExpected` annotation causes the test to fail because it passed when it was expected to fail

### Root Cause:
**Test annotation expects failure with DISABLED, but now gets ENABLED by default**

The test was written to document that EXCLUDE mode doesn't work with DISABLED. Now that ENABLED is the default, the test passes, which violates the `@FailureExpected` annotation.

### Solution:
**Update the test to explicitly use SessionCheckMode.DISABLED:**
```java
@Test @FailureExpected(reason = "EXCLUDE has no effect with DISABLED at least for now")
void testExcludeRemovalsUnorderedNoSessionCheck(SessionFactoryScope factoryScope) {
    factoryScope.inTransaction((session) -> {
        session.remove(session.find(Person.class, 5));

        List<Person> persons = session.findMultiple(
            Person.class,
            List.of(1,2,3,4,5),
            FindMultipleOption.SessionCheckMode.DISABLED,  // ADD THIS
            FindMultipleOption.RemovalsMode.EXCLUDE,
            FindMultipleOption.OrderingMode.UNORDERED
        );
        assertThat(persons).hasSize(4);
        assertThat(persons).doesNotContainNull();
    });
}
```

---

## Summary of Required Fixes

### 1. CacheMode.REFRESH_SESSION Support (2 tests)
**Location:** `AbstractMultiIdEntityLoader.loadFromCaches()`
**Fix:** Check for CacheMode.REFRESH_SESSION and force reload even for managed entities

### 2. Lock Upgrade Support (2 tests)  
**Location:** `AbstractMultiIdEntityLoader.loadFromCaches()`
**Fix:** Either force reload when lock requested, or upgrade lock before returning cached entity

### 3. REPLACE Mode Null Handling (1 test)
**Location:** `AbstractMultiIdEntityLoader.unorderedMultiLoad()`
**Fix:** Verify that REPLACE mode nulls are properly tracked in resolvedPositions

### 4. Deleted Entity Handling (1 test)
**Location:** `AbstractMultiIdEntityLoader.loadFromCaches()`
**Fix:** Ensure REPLACE mode correctly returns null for deleted entities

### 5. Test Annotation Update (1 test)
**Location:** `FindMultipleDocTests.testExcludeRemovalsUnorderedNoSessionCheck()`
**Fix:** Add explicit `SessionCheckMode.DISABLED` to test

---

## Recommendation

**Option 1: Full Compatibility (Recommended for Hibernate 8.0)**
- Implement all fixes above
- Ensure SessionCheckMode.ENABLED works with all existing features
- Update tests that need DISABLED behavior to explicitly specify it

**Option 2: Document Breaking Changes**
- Document that CacheMode.REFRESH_SESSION requires SessionCheckMode.DISABLED
- Document that lock upgrades require SessionCheckMode.DISABLED  
- Update affected tests to use DISABLED mode
- Add migration guide entries

**Option 3: Hybrid Approach**
- Fix critical issues (lock upgrades, REFRESH_SESSION)
- Document minor behavioral changes
- Provide clear migration path for users
