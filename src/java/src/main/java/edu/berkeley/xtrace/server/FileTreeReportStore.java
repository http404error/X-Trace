/*
 * Copyright (c) 2005,2006,2007 The Regents of the University of California.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the University of California, nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE UNIVERSITY OF CALIFORNIA ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE UNIVERSITY OF CALIFORNIA BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package edu.berkeley.xtrace.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import edu.berkeley.xtrace.TaskID;
import edu.berkeley.xtrace.XTraceException;
import edu.berkeley.xtrace.XTraceMetadata;
import edu.berkeley.xtrace.reporting.Report;

public final class FileTreeReportStore implements QueryableReportStore {
	private static final Logger LOG = Logger
			.getLogger(FileTreeReportStore.class);
	// TODO: support user-specified jdbc connect strings
	// TODO: when the FileTreeReportStore loads up, fill in the derby database
	// with
	// metdata about the already stored reports

	private String dataDirName;
	private File dataRootDir;
	private BlockingQueue<String> incomingReports;
	private LRUFileHandleCache fileCache;
	private Connection conn;
	private PreparedStatement countTasks, insert, update, updateTitle,
			updateTags, updatedSince, numByTask, getByTag, totalNumReports,
			totalNumTasks, lastUpdatedByTask, lastTasks, getTags, getByTitle,
			getByTitleApprox;
	private boolean shouldOperate = false;
	private boolean databaseInitialized = false;
  private PreparedStatement tasksBetween;

  private PreparedStatement timesByTask;

	private static final Pattern XTRACE_LINE = Pattern.compile(
			"^X-Trace:\\s+([0-9A-Fa-f]+)$", Pattern.MULTILINE);

	public synchronized void setReportQueue(BlockingQueue<String> q) {
		this.incomingReports = q;
	}

	@SuppressWarnings("serial")
	public synchronized void initialize() throws XTraceException {
		// Directory to store reports into
		dataDirName = System.getProperty("xtrace.server.storedirectory");
		if (dataDirName == null) {
			throw new XTraceException(
					"FileTreeReportStore selected, but no xtrace.server.storedirectory specified");
		}
		dataRootDir = new File(dataDirName);

		if (!dataRootDir.isDirectory()) {
			throw new XTraceException("Data Store location isn't a directory: "
					+ dataDirName);
		}
		if (!dataRootDir.canWrite()) {
			throw new XTraceException("Can't write to data store directory");
		}

		// 25-element LRU file handle cache. The report data is stored here
		fileCache = new LRUFileHandleCache(500, dataRootDir);

		// the embedded database keeps metadata about the reports
		initializeDatabase();

		shouldOperate = true;
	}

	private void initializeDatabase() throws XTraceException {
		// This embedded SQL database contains metadata about the reports
		System.setProperty("derby.system.home", dataDirName);
		try {
			Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
		} catch (InstantiationException e) {
			throw new XTraceException(
					"Unable to instantiate internal database", e);
		} catch (IllegalAccessException e) {
			throw new XTraceException(
					"Unable to access internal database class", e);
		} catch (ClassNotFoundException e) {
			throw new XTraceException(
					"Unable to locate internal database class", e);
		}
		try {
			try {
				// Connect to existing DB
				conn = DriverManager.getConnection("jdbc:derby:tasks");
			} catch (SQLException e) {
				// DB does not exist - create it
				conn = DriverManager
						.getConnection("jdbc:derby:tasks;create=true");
				createTables();
				conn.commit();
			}
			conn.setAutoCommit(false);
		} catch (SQLException e) {
			throw new XTraceException("Unable to connect to interal database: "
					+ e.getSQLState(), e);
		}
		LOG.info("Successfully connected to the internal Derby database");

		try {
			createPreparedStatements();
		} catch (SQLException e) {
			throw new XTraceException("Unable to setup prepared statements", e);
		}
		databaseInitialized = true;
	}

	private void createTables() throws SQLException {
		Statement s = conn.createStatement();
		s.executeUpdate("create table tasks("
				+ "taskId varchar(40) not null primary key, "
				+ "firstSeen timestamp default current_timestamp not null, "
				+ "lastUpdated timestamp default current_timestamp not null, "
				+ "numReports integer default 1 not null, "
				+ "tags varchar(512), " + "title varchar(128))");
		s.executeUpdate("create index idx_tasks on tasks(taskid)");
		s.executeUpdate("create index idx_firstseen on tasks(firstSeen)");
		s.executeUpdate("create index idx_lastUpdated on tasks(lastUpdated)");
		s.executeUpdate("create index idx_tags on tasks(tags)");
		s.executeUpdate("create index idx_title on tasks(title)");
		s.close();
	}

	private void createPreparedStatements() throws SQLException {
		countTasks = conn
				.prepareStatement("select count(taskid) as rowcount from tasks where taskid = ?");
		insert = conn
				.prepareStatement("insert into tasks (taskid, tags, title, numReports) values (?, ?, ?, ?)");
		update = conn
				.prepareStatement("update tasks set lastUpdated = current_timestamp, "
						+ "numReports = numReports + ? where taskId = ?");
		updateTitle = conn
				.prepareStatement("update tasks set title = ? where taskid = ?");
		updateTags = conn
				.prepareStatement("update tasks set tags = ? where taskid = ?");
		updatedSince = conn
				.prepareStatement("select * from tasks where firstseen >= ? order by lastUpdated desc");
		tasksBetween = conn
		    .prepareStatement("select taskid from tasks where firstseen <= ? and lastUpdated >= ?");
		numByTask = conn
				.prepareStatement("select numReports from tasks where taskid = ?");
		totalNumReports = conn
				.prepareStatement("select sum(numReports) as totalreports from tasks");
		totalNumTasks = conn
				.prepareStatement("select count(distinct taskid) as numtasks from tasks");
		timesByTask = conn
		    .prepareStatement("select firstseen, lastUpdated from tasks where taskid = ?");
		lastUpdatedByTask = conn
				.prepareStatement("select lastUpdated from tasks where taskid = ?");
		lastTasks = conn
				.prepareStatement("select * from tasks order by lastUpdated desc");
		getByTag = conn
				.prepareStatement("select * from tasks where upper(tags) like upper('%'||?||'%') order by lastUpdated desc");
		getTags = conn
				.prepareStatement("select tags from tasks where taskid = ?");
		getByTitle = conn
				.prepareStatement("select * from tasks where upper(title) = upper(?) order by lastUpdated desc");
		getByTitleApprox = conn
				.prepareStatement("select * from tasks where upper(title) like upper('%'||?||'%') order by lastUpdated desc");
	}

	public void sync() {
		fileCache.flushAll();
	}

	public synchronized void shutdown() {
		LOG.info("Shutting down the FileTreeReportStore");
		if (fileCache != null)
			fileCache.closeAll();
		
		dbupdater.interrupt();

		if (databaseInitialized) {
			try {
				DriverManager.getConnection("jdbc:derby:tasks;shutdown=true");
			} catch (SQLException e) {
				if (!e.getSQLState().equals("08006")) {
					LOG.warn("Unable to shutdown embedded database", e);
				}
			}
			databaseInitialized = false;
		}
	}
	
	private static final int xtrace_field_offset = Report.REPORT_HEADER_LENGTH + "X-Trace: ".length();
	private static final int expected_newline = xtrace_field_offset + 34;
	void receiveReport(String msg) {
	    // first check if xtrace exists, send to old method if not
	    if (!msg.regionMatches(false, Report.REPORT_HEADER_LENGTH, "X-Trace:", 0, 8)) {
	      receiveReportOld(msg);
	      return;
	    }
	  
      // hard-code position of X-Trace
	    int line_end = expected_newline;
	    if (!msg.regionMatches(line_end, "\n", 0, 1))
	      line_end = msg.indexOf('\n', xtrace_field_offset);
	  
      boolean hasTag = msg.regionMatches(false, line_end+1, "Tag: ", 0, 5);
      boolean hasTitle = msg.regionMatches(false, line_end+1, "Title: ", 0, 7);
      
      // For now, send reports that have tag / title to the old method
      if (hasTag || hasTitle) {
        receiveReportOld(msg);
        return;
      }
      
      XTraceMetadata meta = XTraceMetadata.createFromString(msg.substring(xtrace_field_offset, line_end));
      
      if (meta==null) {
        LOG.warn("Discarding a report due to lack of X-Trace field: " + msg);
        return;
      }
      
      if (meta.getTaskId()==null) {
        LOG.warn("Discarding a report due to lack of taskId: " + msg);
        return;
      }
      
      String taskId = meta.getTaskId().toString();
      
      BufferedWriter fout = fileCache.getHandle(taskId);
      if (fout==null) {
        LOG.warn("Discarding a report due to internal fileCache error: " + msg);
        return;
      }
      
      // Write the report to the file
      try {
          fout.write(msg);
          fout.newLine();
          fout.newLine();
      } catch (IOException e) {
          LOG.warn("I/O error while writing report to file: " + msg, e);
      }
      
      // Write the report metadata to the database
      while(!lock.compareAndSet(false, true)) {}
      try {
        DatabaseUpdate update = pendingupdates.get(taskId);
        if (update==null) {
          update = new DatabaseUpdate(taskId);
          pendingupdates.put(taskId, update);
        }
        update.newreportcount++;
      } finally {
        lock.set(false);
      }
      
	}

	void receiveReportOld(String msg) {
		Matcher matcher = XTRACE_LINE.matcher(msg);
		if (matcher.find()) {
			Report r = Report.createFromString(msg);
			String xtraceLine = matcher.group(1);
			XTraceMetadata meta = XTraceMetadata.createFromString(xtraceLine);

			if (meta.getTaskId() != null) {
				TaskID task = meta.getTaskId();
				String taskId = task.toString().toUpperCase();
				BufferedWriter fout = fileCache.getHandle(taskId);
				if (fout == null) {
					LOG
							.warn("Discarding a report due to internal fileCache error: "
									+ msg);
					return;
				}
				try {
					fout.write(msg);
					fout.newLine();
					fout.newLine();
					LOG.debug("Wrote " + msg.length() + " bytes to the stream");
				} catch (IOException e) {
					LOG.warn("I/O error while writing the report", e);
				}

				// Add update for thread to process
				String title = null;
				List<String> titleVals = r.get("Title");
				if (titleVals != null && titleVals.size() > 0) {
					// There should be only one title field, but if there
					// are more, just
					// arbitrarily take the first one.
					title = titleVals.get(0);
				}

				// Extract tags
				TreeSet<String> newTags = null;
				List<String> list = r.get("Tag");
				if (list != null) {
					newTags = new TreeSet<String>(list);
				}
				
        while(!lock.compareAndSet(false, true)) {}
        try {
  			  DatabaseUpdate update = pendingupdates.get(taskId);
  			  if (update==null) {
  			    update = new DatabaseUpdate(taskId);
  	        pendingupdates.put(taskId, update);
  			  }
  			  
  			  if (newTags!=null && update.tags!=null) update.tags.addAll(newTags);
  			  if (newTags!=null && update.tags==null) update.tags = new HashSet<String>(newTags);
  			  if (title!=null) update.title = title;
  			  update.newreportcount++;
        } finally {
          lock.set(false);
        }

			} else {
				LOG
						.debug("Ignoring a report without an X-Trace taskID: "
								+ msg);
			}
		}
	}
	
	private class DatabaseUpdate {
	  public final String taskid;
	  public DatabaseUpdate(String xtrace) {
	    this.taskid = xtrace;
	  }
	  public String title = null;
	  public HashSet<String> tags = null;
	  public Integer newreportcount = 0;
	}

	private AtomicBoolean lock = new AtomicBoolean();
	private Map<String, DatabaseUpdate> pendingupdates = new HashMap<String, DatabaseUpdate>();

  private Thread dbupdater;
	
  private class IncomingReportDatabaseUpdater extends Thread {
    @Override
    public void run() {
      LOG.info("Database updater thread started");

      Map<String, DatabaseUpdate> toprocess = new HashMap<String, DatabaseUpdate>();
      while (true) {
        if (shouldOperate) {          
          // Copy out the updates
          while (!lock.compareAndSet(false, true)) {}
          try {
            if (pendingupdates.size()>0) {
              Map<String, DatabaseUpdate> temp = pendingupdates;
              pendingupdates = toprocess;
              toprocess = temp;
            }
          } finally {
            lock.set(false);
          }
          
          if (toprocess.size()==0) {
            // Wait if no updates to process
            try {
              Thread.sleep(1000);
            } catch (InterruptedException e) {
              LOG.error("Database updater thread interrupted, ending");
              return;
            }
          } else {
            // Otherwise process all updates
            synchronized (conn) {
              for (DatabaseUpdate update : toprocess.values()) {
                String taskId = update.taskid;
                try {
                  if (!taskExists(taskId))
                    newTask(taskId, update);
                  if (update.title!=null)
                    updateTitle(taskId, update.title);
                  if (update.tags!=null)
                    addTagsToExistingTask(taskId, update.tags);
                  if (update.newreportcount!=null)
                    updateExistingTaskReportCount(taskId, update.newreportcount);
                } catch (SQLException e) {
                  LOG.warn("Error processing database update for task " + taskId + ", dropping database update.  Report will still exist on disk", e);
                }
              } 
              toprocess.clear();
  
              // Commit the updates
              try {
                conn.commit();
              } catch (SQLException e) {
                LOG.warn("Error committing database updates", e);
              }
            }
          }
        }
      }
    }
    
    private synchronized boolean taskExists(String taskId) throws SQLException {
      countTasks.setString(1, taskId);
      ResultSet rs = countTasks.executeQuery();
      rs.next();
      boolean exists = rs.getInt("rowcount") != 0;
      rs.close();
      return exists;
    }
    
    private synchronized void newTask(String taskId, DatabaseUpdate update) throws SQLException {
      String title = update.title == null ? taskId : update.title;
      insert.setString(1, taskId);
      insert.setString(2, joinWithCommas(update.tags));
      insert.setString(3, title);
      insert.setInt(4, update.newreportcount);
      insert.executeUpdate();
    }
    
    private synchronized void updateTitle(String taskId, String title) throws SQLException {
      updateTitle.setString(1, title);
      updateTitle.setString(2, taskId);
      updateTitle.executeUpdate();
    }
    
    private synchronized void updateExistingTaskReportCount(String taskId, Integer reportCount) throws SQLException {
      // Update report count and last-updated date
      update.setInt(1, reportCount);
      update.setString(2, taskId);
      update.executeUpdate();
    }
    
    private synchronized void addTagsToExistingTask(String taskId, HashSet<String> tags) throws SQLException {
      getTags.setString(1, taskId);
      ResultSet tagsRs = getTags.executeQuery();
      tagsRs.next();
      String oldTags = tagsRs.getString("tags");
      tagsRs.close();
      tags.addAll(Arrays.asList(oldTags.split(",")));
      updateTags.setString(1, joinWithCommas(tags));
      updateTags.setString(2, taskId);
      updateTags.executeUpdate();
    }
  }

	private String joinWithCommas(Collection<String> strings) {
		if (strings == null)
			return "";
		StringBuilder sb = new StringBuilder();
		for (Iterator<String> it = strings.iterator(); it.hasNext();) {
			sb.append(it.next());
			if (it.hasNext())
				sb.append(",");
		}
		return sb.toString();
	}

	public void run() {
		LOG.info("FileTreeReportStore running with datadir " + dataDirName);
		
		// Start the database updater
		this.dbupdater = new IncomingReportDatabaseUpdater();
		this.dbupdater.start();

		while (true) {
			if (shouldOperate) {
				String msg;
				try {
					msg = incomingReports.take();
				} catch (InterruptedException e1) {
					continue;
				}
				receiveReport(msg);
			}
		}
	}

	public Iterator<Report> getReportsByTask(TaskID task) {
		return new FileTreeIterator(taskIdtoFile(task.toString()));
	}

	public synchronized List<TaskRecord> getTasksSince(long milliSecondsSince1970,
			int offset, int limit) {
		ArrayList<TaskRecord> lst = new ArrayList<TaskRecord>();

		try {
			if (offset + limit + 1 < 0) {
				updatedSince.setMaxRows(Integer.MAX_VALUE);
			} else {
				updatedSince.setMaxRows(offset + limit + 1);
			}
			updatedSince.setString(1, (new Timestamp(milliSecondsSince1970))
					.toString());
			ResultSet rs = updatedSince.executeQuery();
			int i = 0;
			while (rs.next()) {
				if (i >= offset && i < offset + limit)
					lst.add(readTaskRecord(rs));
				i++;
			}
		} catch (SQLException e) {
			LOG.warn("Internal SQL error", e);
		}

		return lst;
	}
	
	public synchronized Collection<String> getTagsForTask(String taskId) {
	  int retries = 3; // retry because database updater thread can commit while getting tags for task
	  SQLException exception;
	  for (int i = 0; i < retries; i++) {
  	  try {
        getTags.setString(1, taskId);
        getTags.execute();
        ResultSet rs = getTags.getResultSet();
        if (rs.next())
          return Arrays.asList(rs.getString("tags").split(","));
      } catch (SQLException e) {
        exception = e;
      }
	  }
	  return new ArrayList<String>(0);
	}

	public synchronized List<TaskRecord> getLatestTasks(int offset, int limit) {
		int numToFetch = offset + limit;
		List<TaskRecord> lst = new ArrayList<TaskRecord>();
		try {
			if (offset + limit + 1 < 0) {
				lastTasks.setMaxRows(Integer.MAX_VALUE);
			} else {
				lastTasks.setMaxRows(offset + limit + 1);
			}
			ResultSet rs = lastTasks.executeQuery();
			int i = 0;
			while (rs.next() && numToFetch > 0) {
				if (i >= offset && i < offset + limit)
					lst.add(readTaskRecord(rs));
				numToFetch -= 1;
				i++;
			}
			rs.close();
		} catch (SQLException e) {
			LOG.warn("Internal SQL error", e);
		}
		return lst;
	}

	public synchronized List<TaskRecord> getTasksByTag(String tag, int offset, int limit) {
		List<TaskRecord> lst = new ArrayList<TaskRecord>();
		try {
			if (offset + limit + 1 < 0) {
				getByTag.setMaxRows(Integer.MAX_VALUE);
			} else {
				getByTag.setMaxRows(offset + limit + 1);
			}
			getByTag.setString(1, tag);
			ResultSet rs = getByTag.executeQuery();
			int i = 0;
			while (rs.next()) {
				TaskRecord rec = readTaskRecord(rs);
				if (rec.getTags().contains(tag)) { // Make sure the SQL "LIKE"
													// match is exact
					if (i >= offset && i < offset + limit)
						lst.add(rec);
				}
				i++;
			}
			rs.close();
		} catch (SQLException e) {
			LOG.warn("Internal SQL error", e);
		}
		return lst;
	}
	
	@Override
	public synchronized Collection<String> getAllOverlappingTasks(String taskId) {
	  HashSet<String> taskids = new HashSet<String>();
	  List<String> unseen = new ArrayList<String>();
	  taskids.add(taskId);
	  unseen.add(taskId);

	  long lower = Long.MAX_VALUE;
	  long upper = 0;

	  while (!unseen.isEmpty()) {
	    // take the next task ID to process
	    String nextid = unseen.remove(0);

	    try {
  	    // Fetch the timestamps for this task
  	    timesByTask.setString(1, nextid);
  	    timesByTask.execute();
  	    ResultSet rs = timesByTask.getResultSet();
  	    if (!rs.next())
  	      continue;
  	    
  	    // Update the search bounds if necessary
        long firstSeen = rs.getTimestamp("firstseen").getTime();
        long lastUpdated = rs.getTimestamp("lastUpdated").getTime();
        lower = firstSeen < lower ? firstSeen : lower;
        upper = lastUpdated > upper ? lastUpdated : upper;
  
        // Now search for all taskids between these bounds
        tasksBetween.setString(1, new Timestamp(upper).toString());
        tasksBetween.setString(2, new Timestamp(lower).toString());
        tasksBetween.execute();
        rs = tasksBetween.getResultSet();
        while (rs.next()) {
          String toadd = rs.getString("taskid");
          if (!taskids.contains(toadd)) {
            taskids.add(toadd);
            unseen.add(toadd);
          }
        }
	    } catch (SQLException e) {
	      // ignore this taskid
	      System.out.println("SQLException getting overlapping taskIDs:");
	      e.printStackTrace();
	    }
	  }
	  
	  return taskids;
	}
	
	@Override
  public synchronized Collection<String> getOverlappingTasks(String taskId) {
	  HashSet<String> overlaps = new HashSet<String>();
	  overlaps.add(taskId);
	  
	  try {
      // Fetch the timestamps for this task
      timesByTask.setString(1, taskId.toString());
      timesByTask.execute();
      ResultSet rs = timesByTask.getResultSet();
      if (rs.next()) {
        // Update the search bounds if necessary
        long firstSeen = rs.getTimestamp("firstseen").getTime();
        long lastUpdated = rs.getTimestamp("lastUpdated").getTime();
    
        // Now search for all taskids between these bounds
        tasksBetween.setString(1, new Timestamp(lastUpdated).toString());
        tasksBetween.setString(2, new Timestamp(firstSeen).toString());
        tasksBetween.execute();
        rs = tasksBetween.getResultSet();
        while (rs.next()) {
          overlaps.add(rs.getString("taskid"));
        }
      }
	  } catch (SQLException e) {
      // ignore this taskid
      System.out.println("SQLException");
      e.printStackTrace();
	  }
	  
	  return overlaps;
	}

	public synchronized int countByTaskId(TaskID taskId) {
		try {
			numByTask.setString(1, taskId.toString().toUpperCase());
			ResultSet rs = numByTask.executeQuery();
			if (rs.next()) {
				return rs.getInt("numreports");
			}
			rs.close();
		} catch (SQLException e) {
			LOG.warn("Internal SQL error", e);
		}
		return 0;
	}

	public synchronized long lastUpdatedByTaskId(TaskID taskId) {
		long ret = 0L;

		try {
			lastUpdatedByTask.setString(1, taskId.toString());
			ResultSet rs = lastUpdatedByTask.executeQuery();
			if (rs.next()) {
				Timestamp ts = rs.getTimestamp("lastUpdated");
				ret = ts.getTime();
			}
			rs.close();
		} catch (SQLException e) {
			LOG.warn("Internal SQL error", e);
		}

		return ret;
	}

	public List<TaskRecord> createRecordList(ResultSet rs, int offset, int limit)
			throws SQLException {
		List<TaskRecord> lst = new ArrayList<TaskRecord>();
		int i = 0;
		while (rs.next()) {
			if (i >= offset && i < offset + limit)
				lst.add(readTaskRecord(rs));
			i++;
		}
		return lst;
	}

	public synchronized List<TaskRecord> getTasksByTitle(String title, int offset, int limit) {
		List<TaskRecord> lst = new ArrayList<TaskRecord>();
		try {
			if (offset + limit + 1 < 0) {
				getByTitle.setMaxRows(Integer.MAX_VALUE);
			} else {
				getByTitle.setMaxRows(offset + limit + 1);
			}
			getByTitle.setString(1, title);
			lst = createRecordList(getByTitle.executeQuery(), offset, limit);
		} catch (SQLException e) {
			LOG.warn("Internal SQL error", e);
		}
		return lst;
	}

	public synchronized List<TaskRecord> getTasksByTitleSubstring(String title, int offset,
			int limit) {
		List<TaskRecord> lst = new ArrayList<TaskRecord>();
		try {
			if (offset + limit + 1 < 0) {
				getByTitleApprox.setMaxRows(Integer.MAX_VALUE);
			} else {
				getByTitleApprox.setMaxRows(offset + limit + 1);
			}
			getByTitleApprox.setString(1, title);
			lst = createRecordList(getByTitleApprox.executeQuery(), offset,
					limit);
		} catch (SQLException e) {
			LOG.warn("Internal SQL error", e);
		}
		return lst;
	}

	public synchronized int numReports() {
		int total = 0;

		try {
			ResultSet rs = totalNumReports.executeQuery();
			rs.next();
			total = rs.getInt("totalreports");
			rs.close();
		} catch (SQLException e) {
			LOG.warn("Internal SQL error", e);
		}

		return total;
	}

	public synchronized int numTasks() {
		int total = 0;

		try {
			ResultSet rs = totalNumTasks.executeQuery();
			rs.next();
			total = rs.getInt("numtasks");
			rs.close();
		} catch (SQLException e) {
			LOG.warn("Internal SQL error", e);
		}

		return total;
	}

	private File taskIdtoFile(String taskId) {
		File l1 = new File(dataDirName, taskId.substring(0, 2));
//		File l2 = new File(l1, taskId.substring(2, 4));
//		File l3 = new File(l2, taskId.substring(4, 6));
		File taskFile = new File(l1, taskId + ".txt");
		return taskFile;
	}

	public long dataAsOf() {
		return fileCache.lastSynched();
	}

	private TaskRecord readTaskRecord(ResultSet rs) throws SQLException {
		TaskID taskId = TaskID.createFromString(rs.getString("taskId"));
		Date firstSeen = new Date(rs.getTimestamp("firstSeen").getTime());
		Date lastUpdated = new Date(rs.getTimestamp("lastUpdated").getTime());
		String title = rs.getString("title");
		int numReports = rs.getInt("numReports");
		String tagstring = rs.getString("tags");
		if (tagstring==null)
		  tagstring = "";
		List<String> tags = Arrays.asList(tagstring.split(","));
		return new TaskRecord(taskId, firstSeen, lastUpdated, numReports,
				title, tags);
	}

  private final static class LRUFileHandleCache {

    private File dataRootDir;
    private final int VALID_FOR;
    
    private final class Metadata {
      public long last_access_time = System.currentTimeMillis();
      public final BufferedWriter writer;
      public Metadata(File f) throws IOException {
        writer = new BufferedWriter(new FileWriter(f, true), 65536);
      }
      public boolean stale() {
        return last_access_time+VALID_FOR < System.currentTimeMillis();
      }
      public BufferedWriter access() {
        last_access_time = System.currentTimeMillis();
        return writer;
      }
    }
    
    private Map<String, Metadata> fCache = null;
    private long lastSynched;

    @SuppressWarnings("serial")
    public LRUFileHandleCache(int validfor, File dataRootDir)
        throws XTraceException {
      this.VALID_FOR = validfor;
      this.lastSynched = System.currentTimeMillis();
      this.dataRootDir = dataRootDir;

      // a 25-entry, LRU file handle cache
      
      fCache = new LinkedHashMap<String, Metadata>(1, 0.75F, true) {        
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Metadata> eldest) {
          Metadata m = eldest.getValue();
          if (m.stale()) {
            try {
              m.writer.close();
            } catch (IOException e) {
              LOG.warn("Error evicting file for task: " + eldest.getKey(), e);
            }
            return true;
          }
          return false;
        }
      };
    }

    public synchronized BufferedWriter getHandle(String taskstr) throws IllegalArgumentException {
      if (!fCache.containsKey(taskstr)) {
        if (taskstr.length() < 6) {
          throw new IllegalArgumentException("Invalid task id: " + taskstr);
        }
        // Create the appropriate three-level directories (l1, l2, and
        // l3)
        File l1 = new File(dataRootDir, taskstr.substring(0, 2));
//        File l2 = new File(l1, taskstr.substring(2, 4));
//        File l3 = new File(l2, taskstr.substring(4, 6));

        if (!l1.exists()) {
          LOG.debug("Creating directory for task " + taskstr + ": "
              + l1.toString());
          if (!l1.mkdirs()) {
            LOG.warn("Error creating directory " + l1.toString());
            return null;
          }
        } else {
          LOG.debug("Directory " + l1.toString()
              + " already exists; not creating");
        }

        // create the file
        File taskFile = new File(l1, taskstr + ".txt");
        

        // insert the PrintWriter into the cache
        try {
          fCache.put(taskstr, new Metadata(taskFile));
          LOG.debug("Inserting new BufferedWriter into the file cache for task " + taskstr);
        } catch (IOException e) {
          LOG.warn("Interal I/O error", e);
          return null;
        }
      } else {
        LOG.debug("Task " + taskstr + " was already in the cache, no need to insert");
      }
      return fCache.get(taskstr).access();
    }

    public synchronized void flushAll() {
      Iterator<Metadata> iter = fCache.values().iterator();
      while (iter.hasNext()) {
        Metadata m = iter.next();
        try {
          m.writer.flush();
        } catch (IOException e) {
          LOG.warn("I/O error while flushing file", e);
        }
      }

      lastSynched = System.currentTimeMillis();
    }

    public synchronized void closeAll() {
      flushAll();

      /*
       * We can't use an iterator since we would be modifying it when we
       * call fCache.remove()
       */
      String[] taskIds = fCache.keySet().toArray(new String[0]);

      for (int i = 0; i < taskIds.length; i++) {
        LOG.debug("Closing handle for file of " + taskIds[i]);
        Metadata m = fCache.get(taskIds[i]);
        if (m != null) {
          try {
            m.writer.close();
          } catch (IOException e) {
            LOG.warn("I/O error closing file for task "
                + taskIds[i], e);
          }
        }

        fCache.remove(taskIds[i]);
      }
    }

    public long lastSynched() {
      return lastSynched;
    }
  }

	final static class FileTreeIterator implements Iterator<Report> {

		private BufferedReader in = null;
		private Report nextReport = null;

		FileTreeIterator(File taskfile) {

			if (taskfile.exists() && taskfile.canRead()) {
				try {
					in = new BufferedReader(new FileReader(taskfile), 4096);
				} catch (FileNotFoundException e) {
				}
			}

			nextReport = calcNext();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.util.Iterator#hasNext()
		 */
		public boolean hasNext() {
			return nextReport != null;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.util.Iterator#next()
		 */
		public Report next() {
			Report ret = nextReport;
			nextReport = calcNext();
			return ret;
		}

		private Report calcNext() {

			if (in == null) {
				return null;
			}

			// Remember where we are if there isn't a complete report in the
			// stream
			try {
				in.mark(4096);
			} catch (IOException e) {
				LOG.warn("I/O error", e);
				return null;
			}

			// Find the line starting with "X-Trace Report ver"
			String line = null;
			do {
				try {
					line = in.readLine();
				} catch (IOException e) {
					LOG.warn("I/O error", e);
				}
			} while (line != null && !line.startsWith("X-Trace Report ver"));

			if (line == null) {
				// There wasn't a complete
				try {
					in.reset();
				} catch (IOException e) {
					LOG.warn("I/O error", e);
				}
				return null;
			}

			StringBuilder reportbuf = new StringBuilder();
			do {
				reportbuf.append(line + "\n");
				try {
					line = in.readLine();
				} catch (IOException e) {
					LOG.warn("I/O error", e);
					return null;
				}
			} while (line != null && !line.equals(""));

			// Find the end of the report (an empty line)
			return Report.createFromString(reportbuf.toString());
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.util.Iterator#remove()
		 */
		public void remove() {
			throw new UnsupportedOperationException();
		}
	}
}
