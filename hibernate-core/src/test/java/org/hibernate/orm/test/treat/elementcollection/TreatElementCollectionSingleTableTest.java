/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.treat.elementcollection;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;

import jakarta.persistence.criteria.JoinType;

import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Reproducer for: NPE in BaseSqmToSqlAstConverter.withTreatRestriction
 * when using treat() with @ElementCollection join on SINGLE_TABLE inheritance.
 */
@DomainModel(
		annotatedClasses = {
				TreatElementCollectionSingleTableTest.Parent.class,
				TreatElementCollectionSingleTableTest.Child.class
		}
)
@ServiceRegistry
@SessionFactory
public class TreatElementCollectionSingleTableTest {

	@Entity(name = "Parent")
	@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
	@DiscriminatorColumn(name = "dtype")
	public static abstract class Parent {
		@Id
		Long id;
	}

	@Entity(name = "Child")
	@DiscriminatorValue("CHILD")
	public static class Child extends Parent {
		@ElementCollection
		@CollectionTable(
				name = "child_items",
				joinColumns = @JoinColumn(name = "child_id")
		)
		@Column(name = "item_id")
		Set<String> itemIds = new HashSet<>();
	}

	@Test
	public void treatJoinElementCollectionDoesNotThrow(SessionFactoryScope scope) {
		// Persist a Child with one element
		scope.inTransaction( em -> {
			Child c = new Child();
			c.id = 1L;
			c.itemIds.add("A");
			em.persist(c);
		});

		// This used to trigger NPE in withTreatRestriction(...)
		scope.inTransaction( em -> assertDoesNotThrow(() -> {
			var cb = em.getCriteriaBuilder();
			var q  = cb.createQuery(Parent.class);
			var root = q.from(Parent.class);

			// The critical line: treat + join on @ElementCollection
			var join = cb.treat(root, Child.class).join("itemIds", JoinType.LEFT);

			// Force Hibernate to render the join
			q.select(root).where(cb.isNotNull(join));
			em.createQuery(q).getResultList();
		}));
	}
}
