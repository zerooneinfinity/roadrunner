package io.helium.event.changelog;

import java.util.List;

import com.google.common.collect.Lists;

import io.helium.common.Path;

public class ChangeLog {
	private List<ChangeLogEvent>	log	= Lists.newArrayList();

	public List<ChangeLogEvent> getLog() {
		return log;
	}

	public void addLog(ChangeLogEvent event) {
		log.add(event);
	}

	public void addChildAddedLogEntry(String name, Path path, Path parent, Object value,
			boolean hasChildren, long numChildren, String prevChildName, int priority) {
		log.add(new ChildAddedLogEvent(name, path, parent, value, numChildren, prevChildName, priority));
	}

	public void addChildChangedLogEntry(String name, Path path, Path parent, Object value,
			boolean hasChildren, long numChildren, String prevChildName, int priority) {
		if (name != null) {
			log.add(new ChildChangedLogEvent(name, path, parent, value, numChildren, prevChildName,
					priority));
		}
	}

	public void addValueChangedLogEntry(String name, Path path, Path parent, Object value,
			String prevChildName, int priority) {
		log.add(new ValueChangedLogEvent(name, path, parent, value, prevChildName, priority));
	}

	public void addChildRemovedLogEntry(Path path, String name, Object value) {
		log.add(new ChildRemovedLogEvent(path, name, value));
	}

	public void clear() {
		log.clear();
	}
}