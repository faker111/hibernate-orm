/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.criteria.sqm;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.hibernate.NotYetImplementedFor6Exception;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.internal.util.collections.Stack;
import org.hibernate.internal.util.collections.StandardStack;
import org.hibernate.query.NavigablePath;
import org.hibernate.query.criteria.spi.BaseCriteriaVisitor;
import org.hibernate.query.criteria.spi.CompoundSelection;
import org.hibernate.query.criteria.spi.ConstructorSelection;
import org.hibernate.query.criteria.spi.FromImplementor;
import org.hibernate.query.criteria.spi.JoinImplementor;
import org.hibernate.query.criteria.spi.PathImplementor;
import org.hibernate.query.criteria.spi.QueryStructure;
import org.hibernate.query.criteria.spi.RootImplementor;
import org.hibernate.query.criteria.spi.RootQuery;
import org.hibernate.query.criteria.spi.SelectionImplementor;
import org.hibernate.query.sqm.produce.internal.UniqueIdGenerator;
import org.hibernate.query.sqm.tree.SqmQuerySpec;
import org.hibernate.query.sqm.tree.SqmSelectStatement;
import org.hibernate.query.sqm.tree.from.SqmFrom;
import org.hibernate.query.sqm.tree.from.SqmFromClause;
import org.hibernate.query.sqm.tree.from.SqmFromElementSpace;
import org.hibernate.query.sqm.tree.from.SqmNavigableJoin;
import org.hibernate.query.sqm.tree.from.SqmRoot;
import org.hibernate.query.sqm.tree.internal.SqmSelectStatementImpl;
import org.hibernate.query.sqm.tree.predicate.SqmWhereClause;
import org.hibernate.query.sqm.tree.select.SqmDynamicInstantiation;
import org.hibernate.query.sqm.tree.select.SqmDynamicInstantiationArgument;
import org.hibernate.query.sqm.tree.select.SqmSelectClause;
import org.hibernate.query.sqm.tree.select.SqmSelection;

/**
 * @author Steve Ebersole
 */
public class CriteriaQueryToSqmTransformer extends BaseCriteriaVisitor {

	@SuppressWarnings("unchecked")
	public static <R> SqmSelectStatement<R> transform(
			RootQuery<R> query,
			SessionFactoryImplementor sessionFactory) {
		final CriteriaQueryToSqmTransformer me = new CriteriaQueryToSqmTransformer( sessionFactory );
		return me.visitRootQuery( query );
	}


	private final SessionFactoryImplementor sessionFactory;

	private final UniqueIdGenerator uidGenerator = new UniqueIdGenerator();


	private Map<NavigablePath, Set<SqmNavigableJoin>> fetchedJoinsByParentPath;



	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Processing state

	private boolean multiValuedParameterBindingsAllowed = false;



	public CriteriaQueryToSqmTransformer(SessionFactoryImplementor sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	@Override
	public SqmSelectStatement visitRootQuery(RootQuery criteriaQuery) {
		final SqmSelectStatementImpl sqmSelectStatement = new SqmSelectStatementImpl();

		sqmSelectStatement.applyQuerySpec(
				visitQueryStructure( criteriaQuery.getQueryStructure() ),
				fetchedJoinsByParentPath
		);

		return sqmSelectStatement;
	}

	@Override
	public SqmQuerySpec visitQueryStructure(QueryStructure<?> queryStructure) {
		return new SqmQuerySpec(
				visitFromClause( queryStructure ),
				visitSelectClause( queryStructure ),
				visitWhereClause( queryStructure ),
				null,
				null
		);
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// From clause

	private FromClauseElementConsumer fromClauseElementConsumer;

	private final Map<FromImplementor<?,?>, SqmFrom> fromElementMapping = new IdentityHashMap<>();

	@Override
	protected SqmFromClause visitFromClause(QueryStructure<?> querySpec) {
		final SqmFromClause sqmFromClause = new SqmFromClause();

		final Stack<SqmFromElementSpace> rootsStack = new StandardStack<>();

		fromClauseElementConsumer = fromElement -> {
			if ( fromElement instanceof RootImplementor ) {
				final SqmFromElementSpace sqmFromElementSpace = sqmFromClause.makeFromElementSpace();
				rootsStack.push( sqmFromElementSpace );

				final RootImplementor<?> root = (RootImplementor) fromElement;
				final SqmRoot<?> sqmRoot = new SqmRoot<>(
						sqmFromElementSpace,
						uidGenerator.generateUniqueId(),
						root.getAlias(),
						root.getModel()
				);
				sqmFromElementSpace.setRoot( sqmRoot );
				fromElementMapping.put( fromElement, sqmRoot );

				// as part of the from-clause we want to visit joins and fetches as
				// well though not in other clauses - so we handle that here

				root.visitJoins( join -> join.accept( this ) );
				root.visitFetches( fetch -> fetch.accept( this ) );

				return sqmRoot;
			}
			else if ( fromElement instanceof JoinImplementor ) {
				// because we unify the JPA Join and Fetch hierarchies both
				// will match here, which is perfect...
				throw new NotYetImplementedFor6Exception( "Criteria Join/Fetch handling not yet implemented" );
			}

			throw new IllegalArgumentException( "Unanticipated criteria From type - " + fromElement );
		};

		try {
			for ( RootImplementor<?> root : querySpec.getRoots() ) {
				root.accept( this );
			}
		}
		finally {
			fromClauseElementConsumer = null;
		}

		return sqmFromClause;
	}

	@Override
	public Object visitRoot(RootImplementor<?> root) {
		if ( fromClauseElementConsumer != null ) {
			// we are processing the from-clause
			return fromClauseElementConsumer.consume( root );
		}

		// otherwise, we should already have processed root - so find its
		// corresponding SqmRoot and return its NavigableReference

		final SqmRoot sqmRoot = (SqmRoot) fromElementMapping.get( root );
		return sqmRoot.getNavigableReference();
	}



	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Selections clause

	@Override
	protected SqmSelectClause visitSelectClause(QueryStructure<?> querySpec) {
		final SqmSelectClause sqmSelectClause = new SqmSelectClause( querySpec.isDistinct() );

		final SelectionImplementor<?> criteriaSelection = querySpec.getSelection();

		// dynamic-instantiation

		if ( criteriaSelection instanceof ConstructorSelection<?> ) {
			final SqmDynamicInstantiation sqmDynamicInstantiation = visitDynamicInstantiation( (ConstructorSelection<?>) criteriaSelection );
			sqmSelectClause.addSelection( new SqmSelection( sqmDynamicInstantiation, criteriaSelection.getAlias() ) );
		}
		else if ( criteriaSelection instanceof CompoundSelection ) {
			( (CompoundSelection<?>) criteriaSelection ).getSelectionItems().forEach(
					item -> sqmSelectClause.addSelection(
							new SqmSelection(
									item.accept( this ),
									item.getAlias()
							)
					)
			);
		}
		else {
			sqmSelectClause.addSelection(
					new SqmSelection(
							criteriaSelection.accept( this ),
							criteriaSelection.getAlias()
					)
			);
		}

		return sqmSelectClause;
	}

	@Override
	public SqmDynamicInstantiation visitDynamicInstantiation(ConstructorSelection<?> selection) {
		final SqmDynamicInstantiation result;

		if ( selection.getJavaType().equals( List.class ) ) {
			result = SqmDynamicInstantiation.forListInstantiation(
					sessionFactory.getTypeConfiguration().getJavaTypeDescriptorRegistry().getOrMakeJavaDescriptor( List.class )
			);
		}
		else if ( selection.getJavaType().equals( Map.class ) ) {
			result = SqmDynamicInstantiation.forMapInstantiation(
					sessionFactory.getTypeConfiguration().getJavaTypeDescriptorRegistry().getOrMakeJavaDescriptor( Map.class )
			);
		}
		else {
			result = SqmDynamicInstantiation.forClassInstantiation( selection.getJavaTypeDescriptor() );
		}

		selection.getSelectionItems().forEach(
				item -> result.addArgument(
						new SqmDynamicInstantiationArgument(
								item.accept( this ),
								item.getAlias()
						)
				)
		);

		return result;
	}



	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Predicates

	@Override
	protected SqmWhereClause visitWhereClause(QueryStructure<?> querySpec) {
		final SqmWhereClause sqmWhereClause = new SqmWhereClause();

		querySpec.visitRestriction(
				predicate -> sqmWhereClause.setPredicate( predicate.accept( this ) )
		);

		return sqmWhereClause;
	}



	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Support

	protected interface PathConsumer extends Consumer<PathImplementor<?>> {
		void consume(PathImplementor<?> path);
	}


	protected interface FromClauseElementConsumer {
		SqmFrom consume(FromImplementor fromElement);
	}
}
