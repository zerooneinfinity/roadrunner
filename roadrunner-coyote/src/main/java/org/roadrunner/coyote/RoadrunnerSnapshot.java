package org.roadrunner.coyote;

import org.json.JSONObject;
import org.mozilla.javascript.Function;
import org.roadrunner.core.DataService;
import org.roadrunner.core.authorization.AuthorizationService;

import de.skiptag.coyote.api.Coyote;

public class RoadrunnerSnapshot {
	private AuthorizationService authorizationService;
	private DataService dataService;
	private String path;
	private JSONObject value;
	private String parentPath;
	private long numChildren;
	private String name;
	private boolean hasChildren;
	private int priority;
	private String contextName;

	public RoadrunnerSnapshot(AuthorizationService authorizationService,
			DataService dataService, String contextName, String path,
			JSONObject value, String parentPath, long numChildren, String name,
			boolean hasChildren, int priority) {
		super();
		this.authorizationService = authorizationService;
		this.dataService = dataService;
		this.path = path;
		this.contextName = contextName;
		this.value = value;
		this.parentPath = parentPath;
		this.numChildren = numChildren;
		this.name = name;
		this.hasChildren = hasChildren;
		this.priority = priority;
	}

	public RoadrunnerService child(String childPath) {
		return new RoadrunnerService(authorizationService, dataService,
				contextName, path + "/" + childPath);
	}

	public void forEach(Function childAction) {

	}

	public int getPriority() {
		return priority;
	}

	public boolean hasChildren() {
		return hasChildren;
	}

	public String name() {
		return name;
	}

	public long numChildren() {
		return numChildren;
	}

	public RoadrunnerService parent() {
		if (parentPath != null) {
			return new RoadrunnerService(authorizationService, dataService,
					contextName, parentPath);
		} else {
			return null;
		}
	}

	public String path() {
		return path;
	}

	public RoadrunnerService ref() {
		return new RoadrunnerService(authorizationService, dataService,
				contextName, path);
	}

	public Object val() {
		return Coyote.parse(value.toString());
	}
}