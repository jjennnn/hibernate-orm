/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.loading.multiLoad;

import java.util.List;

import org.hibernate.CacheMode;
import org.hibernate.FindMultipleOption;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.cfg.AvailableSettings;

import org.hibernate.testing.orm.junit.JiraKey;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.Table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Test for HHH-20515: Session.findMultiple overwrites in-session changes with 2nd level cache state
 *
 * @author Jennifer Joby
 */
@ServiceRegistry(
		settings = {
				@Setting( name = AvailableSettings.USE_SECOND_LEVEL_CACHE, value = "true" ),
				@Setting( name = AvailableSettings.GENERATE_STATISTICS, value = "true" ),
				@Setting( name = AvailableSettings.JAKARTA_HBM2DDL_DATABASE_ACTION, value = "create-drop" )
		}
)
@DomainModel(
		annotatedClasses = MultiLoadOverwriteTest.CachedEntity.class,
		sharedCacheMode = SharedCacheMode.ENABLE_SELECTIVE
)
@SessionFactory
public class MultiLoadOverwriteTest {

	@BeforeEach
	public void setUp(SessionFactoryScope scope) {
		scope.inTransaction(session -> {
			session.setCacheMode( CacheMode.IGNORE );
			for ( int i = 1; i <= 5; i++ ) {
				session.persist( new CachedEntity( i, "Entity #" + i ) );
			}
		} );
	}

	@AfterEach
	public void tearDown(SessionFactoryScope scope) {
		scope.inTransaction(session -> {
			session.createMutationQuery( "delete from CachedEntity" ).executeUpdate();
		} );
		scope.getSessionFactory().getCache().evictAllRegions();
	}

	@Test
	@JiraKey( value = "HHH-20515" )
	public void testFindMultipleDoesNotOverwriteInSessionChanges(SessionFactoryScope scope) {
		// First, populate the 2nd level cache
		scope.inTransaction(session -> {
			session.find( CachedEntity.class, 1 );
		} );

		// Now test the bug scenario
		scope.inTransaction(session -> {
			// Step 1: Load entity and verify initial state
			CachedEntity entity = session.find( CachedEntity.class, 1 );
			assertNotNull( entity );
			assertEquals( "Entity #1", entity.getName() );

			// Step 2: Modify the entity in-session
			String modifiedName = "Modified Name";
			entity.setName( modifiedName );
			assertEquals( modifiedName, entity.getName() );

			// Step 3: Call findMultiple which should NOT overwrite the change
			List<CachedEntity> entities = session.findMultiple(
					CachedEntity.class,
					List.of( 1, 2, 3 )
			);

			// Step 4: Verify the in-session change is preserved
			assertEquals( 3, entities.size() );
			CachedEntity reloadedEntity = entities.get( 0 );
			assertSame( entity, reloadedEntity, "Should return same managed instance" );
			assertEquals( modifiedName, reloadedEntity.getName(),
					"In-session changes should not be overwritten by cached state" );
			assertEquals( modifiedName, entity.getName(),
					"Original reference should still have modified value" );
		} );
	}

	@Test
	@JiraKey( value = "HHH-20515" )
	public void testFindMultipleDoesNotOverwriteInSessionChangesWithSessionCheckEnabled(SessionFactoryScope scope) {
		// First, populate the 2nd level cache
		scope.inTransaction(session -> {
			session.find( CachedEntity.class, 1 );
		} );

		// Now test with SessionCheckMode.ENABLED
		scope.inTransaction(session -> {
			CachedEntity entity = session.find( CachedEntity.class, 1 );
			assertNotNull( entity );
			assertEquals( "Entity #1", entity.getName() );

			String modifiedName = "Modified with ENABLED";
			entity.setName( modifiedName );

			List<CachedEntity> entities = session.findMultiple(
					CachedEntity.class,
					List.of( 1, 2, 3 ),
					FindMultipleOption.SessionCheckMode.ENABLED
			);

			assertEquals( 3, entities.size() );
			CachedEntity reloadedEntity = entities.get( 0 );
			assertSame( entity, reloadedEntity );
			assertEquals( modifiedName, reloadedEntity.getName(),
					"In-session changes should be preserved even with SessionCheckMode.ENABLED" );
		} );
	}

	@Test
	@JiraKey( value = "HHH-20515" )
	public void testFindMultipleUnorderedDoesNotOverwriteInSessionChanges(SessionFactoryScope scope) {
		// First, populate the 2nd level cache
		scope.inTransaction(session -> {
			session.find( CachedEntity.class, 1 );
		} );

		// Test with unordered multi-load
		scope.inTransaction(session -> {
			CachedEntity entity = session.find( CachedEntity.class, 1 );
			assertNotNull( entity );

			String modifiedName = "Modified Unordered";
			entity.setName( modifiedName );

			List<CachedEntity> entities = session.findMultiple(
					CachedEntity.class,
					List.of( 1, 2, 3 ),
					FindMultipleOption.OrderingMode.UNORDERED
			);

			assertEquals( 3, entities.size() );
			// Find the entity with ID 1 in the unordered list
			CachedEntity reloadedEntity = entities.stream()
					.filter( e -> e.getId().equals( 1 ) )
					.findFirst()
					.orElse( null );
			assertNotNull( reloadedEntity );
			assertSame( entity, reloadedEntity );
			assertEquals( modifiedName, reloadedEntity.getName(),
					"In-session changes should be preserved with unordered multi-load" );
		} );
	}

	@Test
	@JiraKey( value = "HHH-20515" )
	public void testMultipleModifiedEntitiesPreserved(SessionFactoryScope scope) {
		// Populate cache for multiple entities
		scope.inTransaction(session -> {
			session.find( CachedEntity.class, 1 );
			session.find( CachedEntity.class, 2 );
			session.find( CachedEntity.class, 3 );
		} );

		// Test modifying multiple entities
		scope.inTransaction(session -> {
			CachedEntity entity1 = session.find( CachedEntity.class, 1 );
			CachedEntity entity2 = session.find( CachedEntity.class, 2 );
			CachedEntity entity3 = session.find( CachedEntity.class, 3 );

			entity1.setName( "Modified 1" );
			entity2.setName( "Modified 2" );
			entity3.setName( "Modified 3" );

			List<CachedEntity> entities = session.findMultiple(
					CachedEntity.class,
					List.of( 1, 2, 3, 4, 5 )
			);

			assertEquals( 5, entities.size() );
			assertEquals( "Modified 1", entities.get( 0 ).getName() );
			assertEquals( "Modified 2", entities.get( 1 ).getName() );
			assertEquals( "Modified 3", entities.get( 2 ).getName() );
			assertEquals( "Entity #4", entities.get( 3 ).getName() );
			assertEquals( "Entity #5", entities.get( 4 ).getName() );
		} );
	}

	@Entity( name = "CachedEntity" )
	@Table( name = "CachedEntity" )
	@Cacheable
	@Cache( usage = CacheConcurrencyStrategy.READ_WRITE )
	public static class CachedEntity {
		@Id
		private Integer id;
		private String name;

		public CachedEntity() {
		}

		public CachedEntity(Integer id, String name) {
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
