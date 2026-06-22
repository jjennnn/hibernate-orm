/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.bugs;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import java.util.List;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.Test;
/**
 * This template demonstrates how to develop a test case for Hibernate ORM, using its built-in unit test framework.
 * Although ORMStandaloneTestCase is perfectly acceptable as a reproducer, usage of this class is much preferred.
 * Since we nearly always include a regression test with bug fixes, providing your reproducer using this method
 * simplifies the process.
 * <p>
 * What's even better?  Fork hibernate-orm itself, add your test case directly to a module's unit tests, then
 * submit it as a PR!
 */
@DomainModel(
		annotatedClasses = {
				CachedEntity.class,
				NotCachedEntity.class
		},
		// If you use *.hbm.xml mappings, instead of annotations, add the mappings here.
		xmlMappings = {
				// "org/hibernate/test/Foo.hbm.xml",
				// "org/hibernate/test/Bar.hbm.xml"
		}
)
@ServiceRegistry(
		// Add in any settings that are specific to your test.  See resources/hibernate.properties for the defaults.
		settings = {
				// For your own convenience to see generated queries:
				@Setting(name = AvailableSettings.SHOW_SQL, value = "true"),
				@Setting(name = AvailableSettings.FORMAT_SQL, value = "true"),
				// @Setting( name = AvailableSettings.GENERATE_STATISTICS, value = "true" ),
				// Add your own settings that are a part of your quarkus configuration:
				// @Setting( name = AvailableSettings.SOME_CONFIGURATION_PROPERTY, value = "SOME_VALUE" ),
		}
)
@SessionFactory
class ORMUnitTestCase {
	@Test
	void hhh20515TestNotCached(SessionFactoryScope scope) throws Exception {
		scope.inTransaction( session -> {
			session.persist(new NotCachedEntity(1L, "name"));
		} );
		scope.inTransaction( session -> {
			NotCachedEntity entity = session.find(NotCachedEntity.class, 1L);
			assertEquals("name", entity.getName());
			entity.setName("newName");
			assertEquals("newName", entity.getName());
			NotCachedEntity entity2 = session.find(NotCachedEntity.class, 1L);
			assertSame(entity, entity2);
			assertEquals("newName", entity2.getName());
			NotCachedEntity entity3 = session.findMultiple(NotCachedEntity.class, List.of(1L)).get(0);
			assertSame(entity, entity3);
			assertEquals("newName", entity3.getName());
			NotCachedEntity entity4 = session.byMultipleIds(NotCachedEntity.class).multiLoad(1L).get(0);
			assertSame(entity, entity4);
			assertEquals("newName", entity4.getName());
		} );
	}
	// Add your tests, using standard JUnit 5.
	@Test
	void hhh20515TestCachedFindMultiple(SessionFactoryScope scope) throws Exception {
		scope.inTransaction( session -> {
			session.persist(new CachedEntity(1L, "name"));
		} );
		scope.inTransaction( session -> {
			CachedEntity entity = session.find(CachedEntity.class, 1L);
			assertEquals("name", entity.getName());
			entity.setName("newName");
			assertEquals("newName", entity.getName());
			CachedEntity entity2 = session.find(CachedEntity.class, 1L);
			assertSame(entity, entity2);
			assertEquals("newName", entity2.getName());
			CachedEntity entity3 = session.findMultiple(CachedEntity.class, List.of(1L)).get(0);
			assertSame(entity, entity3);
			assertEquals("newName", entity3.getName());
			CachedEntity entity4 = session.byMultipleIds(CachedEntity.class).multiLoad(1L).get(0);
			assertSame(entity, entity4);
			assertEquals("newName", entity4.getName());
		} );
	}
}
