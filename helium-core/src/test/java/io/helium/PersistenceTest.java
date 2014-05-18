package io.helium;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import io.helium.authorization.Authorization;
import io.helium.authorization.rulebased.RuleBasedAuthorization;
import io.helium.common.Path;
import io.helium.event.changelog.ChangeLog;
import io.helium.json.Node;
import io.helium.persistence.inmemory.InMemoryPersistence;

public class PersistenceTest {

	private InMemoryPersistence persistence;

	@Before
	public void setUp() throws Exception {
		persistence = new InMemoryPersistence(new RuleBasedAuthorization(
				Authorization.ALL_ACCESS_RULE), new Helium(PersistenceProcessorTest.BASE_PATH));
	}

	@Test
	public void setSimpleValueTest() {
		Path path = new Path("/test/test");
		Assert.assertNotNull(persistence.get(path));
		Assert.assertNotNull(persistence.getNode(path));
		persistence.applyNewValue(new ChangeLog(), new Node(), path.append("msg"), 1, "HalloWelt");
		Assert.assertEquals(persistence.get(path.append("msg")), "HalloWelt");
	}

	@Test
	public void setNodeValueTest() {
		Path path = new Path("/test/test");
		Assert.assertNotNull(persistence.get(path));
		Assert.assertNotNull(persistence.getNode(path));
		persistence.applyNewValue(new ChangeLog(), new Node(), path, 2,
				new Node().put("msg", "HalloWelt"));
		Assert.assertEquals(persistence.get(path.append("msg")), "HalloWelt");
		persistence.applyNewValue(new ChangeLog(), new Node(), path.append("msg"), 1, "HalloWelt2");
		Assert.assertEquals(persistence.get(path.append("msg")), "HalloWelt2");
	}
}