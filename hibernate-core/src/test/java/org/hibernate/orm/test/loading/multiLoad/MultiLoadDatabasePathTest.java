/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.loading.multiLoad;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.JiraKey;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Test that verifies the database loading path respects managed entities
 * even with SessionCheckMode.DISABLED (the default).
 *
 * This test uses non-cached entities to force the database loading path,
 * demonstrating that handleResults() always checks the persistence context
 * regardless of SessionCheckMode setting.
 */
@DomainModel(annotatedClasses = {MultiLoadDatabasePathTest.NonCachedEntity.class})
@SessionFactory
@ServiceRegistry(settings = {
	@Setting(name = AvailableSettings.USE_SECOND_LEVEL_CACHE, value = "false")
})
public class MultiLoadDatabasePathTest {

	@BeforeEach
	public void setUp(SessionFactoryScope scope) {
		scope.inTransaction(session -> {
			for (int i = 1; i <= 5; i++) {
				session.persist(new NonCachedEntity(i, "Entity #" + i));
			}
		});
	}

	@Test
	@JiraKey("HHH-20515")
	public void testDatabasePathRespectsManagedEntityWithDisabledSessionCheck(SessionFactoryScope scope) {
		scope.inTransaction(session -> {
			// Load entity - goes to database, adds to persistence context
			NonCachedEntity entity = session.find(NonCachedEntity.class, 1);
			assertNotNull(entity);
			assertEquals("Entity #1", entity.getName());

			// Modify the managed entity
			String modifiedName = "Modified in Session";
			entity.setName(modifiedName);

			// Call findMultiple with SessionCheckMode.DISABLED (default)
			// This will:
			// 1. Skip session check (because DISABLED)
			// 2. Query database for ALL IDs including id=1
			// 3. Database returns old value "Entity #1"
			// 4. handleResults() checks persistence context
			// 5. Finds managed entity, returns it (preserving changes)
			List<NonCachedEntity> entities = session.findMultiple(
				NonCachedEntity.class,
				List.of(1, 2, 3)
				// SessionCheckMode.DISABLED is the default
			);

			assertEquals(3, entities.size());
			NonCachedEntity reloadedEntity = entities.get(0);

			// CRITICAL ASSERTIONS:
			assertSame(entity, reloadedEntity,
				"Should return same instance from persistence context");
			assertEquals(modifiedName, reloadedEntity.getName(),
				"Database path should preserve in-session changes even with SessionCheckMode.DISABLED");
		});
	}

	@Entity(name = "NonCachedEntity")
	@Table(name = "NonCachedEntity")
	// NO @Cache annotation - forces database loading path
	public static class NonCachedEntity {
		@Id
		private Integer id;
		private String name;

		public NonCachedEntity() {}

		public NonCachedEntity(Integer id, String name) {
			this.id = id;
			this.name = name;
		}

		public Integer getId() {
			return id;
		}

		public void setId(Integer id) {
			this.id = id;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}
	}
}
