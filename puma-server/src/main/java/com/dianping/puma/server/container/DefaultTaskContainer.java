package com.dianping.puma.server.container;

import com.dianping.puma.core.model.Table;
import com.dianping.puma.core.model.TableSet;
import com.dianping.puma.core.registry.RegistryService;
import com.dianping.puma.instance.InstanceManager;
import com.dianping.puma.server.builder.TaskBuilder;
import com.dianping.puma.server.server.TaskServerManager;
import com.dianping.puma.status.SystemStatusManager;
import com.dianping.puma.storage.EventStorage;
import com.dianping.puma.storage.holder.BinlogInfoHolder;
import com.dianping.puma.storage.manage.DatabaseStorageManager;
import com.dianping.puma.taskexecutor.TaskExecutor;
import com.dianping.puma.taskexecutor.task.DatabaseTask;
import com.dianping.puma.taskexecutor.task.InstanceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DefaultTaskContainer implements TaskContainer {

	private final Logger logger = LoggerFactory.getLogger(DefaultTaskContainer.class);

	@Autowired
	InstanceManager instanceManager;

	@Autowired
	BinlogInfoHolder binlogInfoHolder;

	@Autowired
	DatabaseStorageManager databaseStorageManager;

	@Autowired
	RegistryService registryService;

	@Autowired
	TaskServerManager taskServerManager;

	@Autowired
	TaskBuilder taskBuilder;

	public static DefaultTaskContainer instance;

	private ExecutorService pool = Executors.newCachedThreadPool();

	private Map<String, TaskExecutor> taskExecutors = new HashMap<String, TaskExecutor>();

	@Override
	public EventStorage getTaskStorage(String database) {
		for (TaskExecutor taskExecutor : taskExecutors.values()) {
			TableSet tableSet = taskExecutor.getTask().getTableSet();
			List<Table> tables = tableSet.listSchemaTables();

			for (Table table : tables) {
				if (table.getSchemaName().equals(database)) {
					return taskExecutor.getFileSender().get(0).getStorage(database);
				}
			}
		}

		return null;
	}

	@Override
	public TaskExecutor getExecutor(String database) {
		return taskExecutors.get(database);
	}

	@Override
	public Map<String, DatabaseTask> getDatabaseTasks() {
		Map<String, DatabaseTask> databaseTaskMap = new HashMap<String, DatabaseTask>();
		for (TaskExecutor taskExecutor: taskExecutors.values()) {
			InstanceTask instanceTask = taskExecutor.getInstanceTask();
			if (instanceTask != null) {
				List<DatabaseTask> databaseTasks = instanceTask.getDatabaseTasks();
				if (databaseTasks != null) {
					for (DatabaseTask databaseTask: databaseTasks) {
						String database = databaseTask.getDatabase();
						databaseTaskMap.put(database, databaseTask);
					}
				}
			}
		}
		return databaseTaskMap;
	}

	@Override
	public Map<String, TaskExecutor> getMainExecutors() {
		Map<String, TaskExecutor> mainExecutors = new HashMap<String, TaskExecutor>();
		for (TaskExecutor taskExecutor: taskExecutors.values()) {
			InstanceTask instanceTask = taskExecutor.getInstanceTask();
			if (instanceTask != null) {
				if (instanceTask.isMain()) {
					mainExecutors.put(instanceTask.getInstance(), taskExecutor);
				}
			}
		}
		return mainExecutors;
	}

	@Override
	public Map<String, List<TaskExecutor>> getTempExecutors() {
		Map<String, List<TaskExecutor>> tempExecutors = new HashMap<String, List<TaskExecutor>>();
		for (TaskExecutor taskExecutor: taskExecutors.values()) {
			InstanceTask instanceTask = taskExecutor.getInstanceTask();
			if (instanceTask != null) {
				if (!instanceTask.isMain()) {
					List<TaskExecutor> taskExecutorList = tempExecutors.get(instanceTask.getInstance());
					if (taskExecutorList == null) {
						taskExecutorList = new ArrayList<TaskExecutor>();
						tempExecutors.put(instanceTask.getInstance(), taskExecutorList);
					}
					taskExecutorList.add(taskExecutor);
				}
			}
		}
		return tempExecutors;
	}

	@Override
	public void create(InstanceTask instanceTask) {
		logger.info("start creating instance task...");
		logger.info("instance task: {}.", instanceTask);

		if (instanceTask.isMain()) {
			createMain(instanceTask);
		} else {
			createTemp(instanceTask);
		}

		for (DatabaseTask databaseTask: instanceTask.getDatabaseTasks()) {
			registryService.register(taskServerManager.findSelfHost(), databaseTask.getDatabase());
		}

		logger.info("success to create instance task.");
	}

	@Override
	public void create(DatabaseTask databaseTask) {
		logger.info("start creating temp task...");
		logger.info("database task: {}.", databaseTask);

		String database = databaseTask.getDatabase();
		String instance = findInstance(database);

		InstanceTask instanceTask = new InstanceTask(count(instance) == 0, instance, databaseTask);
		TaskExecutor taskExecutor = taskBuilder.build(instanceTask);
		start(taskExecutor);
		add(taskExecutor);

		registryService.register(taskServerManager.findSelfHost(), database);

		logger.info("success to create task.");
	}

	protected void createMain(InstanceTask instanceTask) {
		logger.info("start creating main instance task...");

		TaskExecutor mainTaskExecutor = taskBuilder.build(instanceTask);

		start(mainTaskExecutor);

		add(mainTaskExecutor);

		logger.info("success to create main instance task.");
	}

	protected void createTemp(InstanceTask instanceTask) {
		logger.info("start creating temp instance task...");

		TaskExecutor tempTaskExecutor = taskBuilder.build(instanceTask);

		start(tempTaskExecutor);

		add(tempTaskExecutor);

		logger.info("success to create temp instance task.");
	}

	@Override
	public void update(DatabaseTask databaseTask) {
		String database = databaseTask.getDatabase();
		remove(database);
		create(databaseTask);
	}

	@Override
	public void remove(String database) {
		logger.info("start removing database task..., database = {}.", database);

		TaskExecutor taskExecutor = findTaskExecutor(database);
		stop(taskExecutor);

		// Remove database level.
		unregisterDatabase(database);
		clearDatabase(database);

		InstanceTask instanceTask = taskExecutor.getInstanceTask();
		instanceTask.remove(database);

		String taskName = instanceTask.getTaskName();
		if (instanceTask.size() == 0) {
			// Remove task level.
			if (taskName != null) {
				unregisterTask(taskName);
				clearTask(taskName);
			}
		} else {
			unregisterTask(taskName);

			TaskExecutor newTaskExecutor = taskBuilder.build(instanceTask);
			start(newTaskExecutor);

			registerTask(newTaskExecutor);
		}

		registryService.unregister(taskServerManager.findSelfHost(), database);

		logger.info("success to remove task.");
	}

	public void merge(TaskExecutor mainTaskExecutor, TaskExecutor tempTaskExecutor) {
		if (!(mainTaskExecutor.isMerging() && mainTaskExecutor.isStop()
				&& tempTaskExecutor.isMerging() && tempTaskExecutor.isStop())) {
			return;
		}

		InstanceTask mainInstanceTask = mainTaskExecutor.getInstanceTask();
		InstanceTask tempInstanceTask = tempTaskExecutor.getInstanceTask();
		mainInstanceTask.merge(tempInstanceTask);

		String tempTaskName = tempInstanceTask.getTaskName();
		unregisterTask(tempTaskName);
		clearTask(tempTaskName);

		TaskExecutor newTaskExecutor = taskBuilder.build(mainInstanceTask);
		start(newTaskExecutor);
		registerTask(newTaskExecutor);
	}

	public void upgrade(TaskExecutor taskExecutor) {
		logger.info("start upgrading task executor...");

		InstanceTask instanceTask = taskExecutor.getInstanceTask();
		if (instanceTask.isMain()) {
			return;
		}

		stop(taskExecutor);
		String oriTaskName = instanceTask.getTaskName();
		unregisterTask(oriTaskName);

		instanceTask.temp2Main();
		String taskName = instanceTask.getTaskName();
		binlogInfoHolder.rename(oriTaskName, taskName);
		binlogInfoHolder.remove(oriTaskName);

		TaskExecutor newTaskExecutor = taskBuilder.build(instanceTask);
		start(newTaskExecutor);
		registerTask(newTaskExecutor);

		logger.info("success to upgrade task executor.");
	}

	protected void add(TaskExecutor taskExecutor) {
		InstanceTask instanceTask = taskExecutor.getInstanceTask();
		if (instanceTask != null) {
			List<DatabaseTask> databaseTasks = instanceTask.getDatabaseTasks();
			if (databaseTasks != null) {
				for (DatabaseTask databaseTask: databaseTasks) {
					String database = databaseTask.getDatabase();
					if (database != null) {
						taskExecutors.put(database, taskExecutor);
					}
				}
			}
		}
	}

	protected void registerDatabase(String database) {
		taskExecutors.remove(database);
	}

	protected void unregisterDatabase(String database) {
		taskExecutors.remove(database);
	}

	protected void registerTask(TaskExecutor taskExecutor) {
		InstanceTask instanceTask = taskExecutor.getInstanceTask();
		if (instanceTask != null) {
			List<DatabaseTask> databaseTasks = instanceTask.getDatabaseTasks();
			if (databaseTasks != null) {
				for (DatabaseTask databaseTask: databaseTasks) {
					String database = databaseTask.getDatabase();
					taskExecutors.put(database, taskExecutor);
				}
			}
		}
	}

	protected void unregisterTask(String taskName) {
		List<String> databases = new ArrayList<String>();

		for (Map.Entry<String, TaskExecutor> entry: taskExecutors.entrySet()) {
			String database = entry.getKey();
			TaskExecutor taskExecutor = entry.getValue();
			InstanceTask instanceTask = taskExecutor.getInstanceTask();
			if (instanceTask != null) {
				if (taskName.equals(instanceTask.getTaskName())) {
					databases.add(database);
				}
			}
		}

		for (String database: databases) {
			taskExecutors.remove(database);
		}

		SystemStatusManager.deleteServer(taskName);
	}

	protected void clearDatabase(String database) {
		databaseStorageManager.delete(database);
	}

	protected void clearTask(String taskName) {
		binlogInfoHolder.remove(taskName);
	}

	protected int count(String instance) {
		List<TaskExecutor> taskExecutorList = new ArrayList<TaskExecutor>();
		for (TaskExecutor taskExecutor: taskExecutors.values()) {
			InstanceTask instanceTask = taskExecutor.getInstanceTask();
			if (instanceTask != null && instance.equals(instanceTask.getInstance())) {
				taskExecutorList.add(taskExecutor);
			}
		}
		return taskExecutorList.size();
	}

	protected TaskExecutor findTaskExecutor(String database) {
		return taskExecutors.get(database);
	}

	protected String findInstance(String database) {
		return instanceManager.getClusterByDb(database);
	}

	public void start(final TaskExecutor taskExecutor) {
		if (taskExecutor == null) {
			throw new NullPointerException("task executor");
		}

		try {
			pool.execute(new Runnable() {
				@Override
				public void run() {
					try {
						taskExecutor.start();
					} catch (Throwable t) {
						logger.error("task executor error occurs.", t);
					}
				}
			});
		} catch (Throwable t) {
			throw new RuntimeException("failed to start task executor.", t);
		}
	}

	public void stop(final TaskExecutor taskExecutor) {
		if (taskExecutor == null) {
			throw new NullPointerException("task executor");
		}

		try {
			taskExecutor.stop();
		} catch (Throwable t) {
			throw new RuntimeException("failed to stop task executor.", t);
		}
	}
}