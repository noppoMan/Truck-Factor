package aserg.gtf;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileWriter;
import java.io.BufferedWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import org.apache.log4j.Logger;

import aserg.gtf.model.LogCommitInfo;
import aserg.gtf.model.NewFileInfo;
import aserg.gtf.model.authorship.AuthorshipInfo;
import aserg.gtf.model.authorship.Developer;
import aserg.gtf.model.authorship.File;
import aserg.gtf.model.authorship.Repository;
import aserg.gtf.task.DOACalculator;
import aserg.gtf.task.NewAliasHandler;
import aserg.gtf.task.extractor.FileInfoExtractor;
import aserg.gtf.task.extractor.GitLogExtractor;
import aserg.gtf.task.extractor.LinguistExtractor;
import aserg.gtf.truckfactor.GreedyTruckFactor;
import aserg.gtf.truckfactor.PrunedGreedyTruckFactor;
import aserg.gtf.truckfactor.TFInfo;
import aserg.gtf.truckfactor.TruckFactor;
import aserg.gtf.util.ConfigInfo;
import aserg.gtf.util.FileInfoReader;
import aserg.gtf.util.LineInfo;

public class GitTruckFactor {
	private static final Logger LOGGER = Logger.getLogger(GitTruckFactor.class);
	private static Properties properties = new Properties();
	private static InputStream input = null;
	public static ConfigInfo config = null;
	public static void main(String[] args) {
		LOGGER.trace("GitTruckFactor starts");
		
		loadConfiguration();
		
		String repositoryPath = "";
		String repositoryName = "";
		boolean dailyAnalysis = false;
		String outputFile = null;
		
		if (args.length>0)
			repositoryPath = args[0];
		if (args.length>1)
			repositoryName = args[1];
		
		// Parse command line arguments
		for (int i = 2; i < args.length; i++) {
			if (args[i].equals("--daily")) {
				dailyAnalysis = true;
			} else if (args[i].equals("--output") && i + 1 < args.length) {
				outputFile = args[i + 1];
				i++; // Skip the next argument as it's the file path
			}
		}
		
		repositoryPath = (repositoryPath.charAt(repositoryPath.length()-1) == '/') ? repositoryPath : (repositoryPath + "/");
		if (repositoryName.isEmpty())
			repositoryName = repositoryPath.split("/")[repositoryPath.split("/").length-1];
		

		Map<String, List<LineInfo>> filesInfo;
		Map<String, List<LineInfo>> aliasInfo;
		Map<String, List<LineInfo>> modulesInfo;
		try {
			filesInfo = FileInfoReader.getFileInfo("repo_info/filtered-files.txt");
		} catch (IOException e) {
			LOGGER.warn("Not possible to read repo_info/filtered-files.txt file. File filter step will not be executed!");
			filesInfo = null;
		}		
		try {
			aliasInfo = FileInfoReader.getFileInfo("repo_info/alias.txt");
		} catch (IOException e) {
			LOGGER.warn("Not possible to read repo_info/alias.txt file. Aliases treating step will not be executed!");
			aliasInfo = null;
		}
		try {
			modulesInfo = FileInfoReader.getFileInfo("repo_info/modules.txt");
		} catch (IOException e) {
			LOGGER.warn("Not possible to read repo_info/modules.txt file. No modules info will be setted!");
			modulesInfo = null;
		}
		
		
		FileInfoExtractor fileExtractor = new FileInfoExtractor(repositoryPath, repositoryName);
		LinguistExtractor linguistExtractor =  new LinguistExtractor(repositoryPath, repositoryName);
		NewAliasHandler aliasHandler =  aliasInfo == null ? null : new NewAliasHandler(aliasInfo.get(repositoryName));
		GitLogExtractor gitLogExtractor = new GitLogExtractor(repositoryPath, repositoryName);	
		
		//Persist commit info
		//gitLogExtractor.persist(commits);
		
		
		try {
			if (dailyAnalysis) {
				printTruckFactorDaily(repositoryPath, repositoryName, filesInfo, modulesInfo,
					fileExtractor, linguistExtractor, gitLogExtractor, aliasHandler, outputFile);
			} else {
				TFInfo tf = getTFInfo(repositoryPath, repositoryName, filesInfo, modulesInfo,	fileExtractor, linguistExtractor, gitLogExtractor,aliasHandler);
				System.out.println(tf);
			}
		} catch (Exception e) {
			LOGGER.error("TF calculation aborted!",e);
		}
		

		
		
		LOGGER.trace("GitTruckFactor end");
	}

	private static TFInfo getTFInfo(String repositoryPath,
			String repositoryName, 
			Map<String, List<LineInfo>> filesInfo, 
			Map<String, List<LineInfo>> modulesInfo,
			FileInfoExtractor fileExtractor,
			LinguistExtractor linguistExtractor,
			GitLogExtractor gitLogExtractor, NewAliasHandler aliasHandler) throws Exception {
		
			Map<String, LogCommitInfo> commits = gitLogExtractor.execute();
 			if (aliasHandler != null)
 					commits = aliasHandler.execute(repositoryName, commits);
 			 				
			List<NewFileInfo> files = fileExtractor.execute();
			files = linguistExtractor.setNotLinguist(files);	
			if(filesInfo != null && filesInfo.size()>0) 
				if(filesInfo.containsKey(repositoryName))
					applyFilterFiles(filesInfo.get(repositoryName), files);
				else
					LOGGER.warn("No filesInfo for " + repositoryName);
			
			if(modulesInfo != null && modulesInfo.containsKey(repositoryName))
				setModules(modulesInfo.get(repositoryName), files);
			
			//Persist file info
			//fileExtractor.persist(files);
			
			DOACalculator doaCalculator = new DOACalculator(repositoryPath, repositoryName, commits.values(), files);
			Repository repository = doaCalculator.execute();
			
			//printFileAuthors(repository);
			//Persist authors info
			//doaCalculator.persist(repository);
			
			TruckFactor truckFactor = new PrunedGreedyTruckFactor(config.getMinPercentage());
			return truckFactor.getTruckFactor(repository);
			
	}

	public static void loadConfiguration() {
		try {
			input = new FileInputStream("config.properties");
			properties.load(input);
			float normalizedDOA = Float.parseFloat((String) properties.get("normalizedDOA"));
			float absoluteDOA = Float.parseFloat((String) properties.get("absoluteDOA"));
			float tfCoverage = Float.parseFloat((String) properties.get("tfCoverage"));
			float minPercentage = Float.parseFloat((String) properties.get("minPercentage"));
			config = new ConfigInfo(normalizedDOA, absoluteDOA, tfCoverage, minPercentage);
		} catch (IOException e1) {
			LOGGER.error("Load configuration info aborted!",e1);
		}
	}

	private static void filterModule(List<NewFileInfo> files, String module) {
		int count = 0;
		for (NewFileInfo file : files) {
			if (file.getModule().equals(module)){
				file.setFiltered(true);
				file.setFilterInfo("Module-filter");
			}
			else if (!file.getFiltered())
				count++;
		}
		LOGGER.info("Files without "+module + ": "+count);
	}
	private static void filterByModule(List<NewFileInfo> files, String module) {
		int count = 0;
		for (NewFileInfo file : files) {
			if (!file.getModule().equals(module)){
				file.setFiltered(true);
				file.setFilterInfo("Module-filter");
			}
			else if (!file.getFiltered())
				count++;
		}
		LOGGER.info(module + " - files: " +count);
	}

	private static void setModules(List<LineInfo> modulesInfo,
			List<NewFileInfo> files) {
		Map<String, String> moduleMap =  new HashMap<String, String>();
		for (LineInfo lineInfo : modulesInfo) {
			moduleMap.put(lineInfo.getValues().get(0), lineInfo.getValues().get(1));
		}
		for (NewFileInfo newFileInfo : files) {
			if (moduleMap.containsKey(newFileInfo.getPath()))
				newFileInfo.setModule(moduleMap.get(newFileInfo.getPath()));
			else
				LOGGER.warn("Alert: module not found for file "+newFileInfo.getPath());
		}
		
	}

	private static void printCoverage(Repository repository, List<String> devsName, Calendar cal) {
		int nFiles = getTotalFiles(repository.getDevelopers());
		Set<String> devsFiles =  new HashSet<String>();
		for (String devName : devsName) {
			for (Developer developer : repository.getDevelopers()) {
				if (developer.getName().equalsIgnoreCase(devName)){
					devsFiles.addAll(getAuthorFiles(developer));
					
				}
					
			}
		}
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
		System.out.println("Coverage;"+ format.format(cal.getTime()) +";"+ devsFiles.size() +";"+ nFiles +";"+ (float)devsFiles.size()/nFiles*100);
	}

	private static int getTotalFiles(List<Developer> developers) {
		Set<String> files = new HashSet<String>();
		for (Developer developer : developers) {
			for (AuthorshipInfo authorship : developer.getAuthorshipInfos()) {
				files.add(authorship.getFile().getPath());
			}
		}
		return files.size();
	}

	private static Set<String> getAuthorFiles(Developer developer) {
		Set<String> paths = new HashSet<String>();
		for (AuthorshipInfo authorship : developer.getAuthorshipInfos()) {
			if (authorship.isDOAAuthor())
				paths.add(authorship.getFile().getPath());
		}		
		return paths;
	}

	private static Map<String, LogCommitInfo> filterCommitsByDate(
			Map<String, LogCommitInfo> commits, Date endDate) {
		Map<String, LogCommitInfo> newCommits =  new HashMap<String, LogCommitInfo>(commits);
		for (Entry<String, LogCommitInfo> entry : commits.entrySet()) {
			if (entry.getValue().getAuthorDate().after(endDate))
				newCommits.remove(entry.getKey());
		}
		return newCommits;
	}

	private static void applyRegexFilter(List<NewFileInfo> files, String exp) {
		int count = 0;
		for (NewFileInfo newFileInfo : files) {
			if (!newFileInfo.getFiltered()){
				if (newFileInfo.getPath().matches(exp)){
					count++;
					newFileInfo.setFiltered(true);
					newFileInfo.setFilterInfo("REGEX: "+ exp);
				}			
			}
		}
		LOGGER.info("REGEX FILTER = " + count);
	}
	
	private static void applyRegexSelect(List<NewFileInfo> files, String exp) {
		int count = 0;
		for (NewFileInfo newFileInfo : files) {
			if (!newFileInfo.getFiltered()){
				if (!newFileInfo.getPath().matches(exp)){
					count++;
					newFileInfo.setFiltered(true);
					newFileInfo.setFilterInfo("REGEX: "+ exp);
				}			
			}
		}
		LOGGER.info("REGEX FILTER = " + count);
	}

	private static void applyFilterFiles(List<LineInfo> filteredFilesInfo, List<NewFileInfo> files) {
		if (filteredFilesInfo != null ){
			for (LineInfo lineInfo : filteredFilesInfo) {
				String path = lineInfo.getValues().get(0);
				for (NewFileInfo newFileInfo : files) {
					if (newFileInfo.getPath().equals(path)){
						newFileInfo.setFiltered(true);
						newFileInfo.setFilterInfo(lineInfo.getValues().get(1));
					}
					
				}
			}
		}
	}

	//Test: calculates the authorship variation on time 
	private static void printCoverageInTime(String repositoryPath,
				String repositoryName, Map<String, List<LineInfo>> filesInfo,
				FileInfoExtractor fileExtractor,
				LinguistExtractor linguistExtractor,
				GitLogExtractor gitLogExtractor, NewAliasHandler aliasHandler) throws Exception {
			
			Calendar cal = Calendar.getInstance();
			cal.set(Calendar.YEAR, 2015);
			cal.set(Calendar.MONTH, Calendar.FEBRUARY);
			cal.set(Calendar.DAY_OF_MONTH, 25);
			String devsA[] = {"Yoann Delouis", "yDelouis"};
			//String devsA[] = {"WonderCsabo", "Damien"};
	 		List<String> devs = Arrays.asList(devsA);
	 		

			//cal.add(Calendar.MONTH, -24);

	 		for (int i = 0; i < 24; i++) {
	 			Map<String, LogCommitInfo> commits = gitLogExtractor.execute();
	 			commits = aliasHandler.execute(repositoryName, commits);
	 			
	 			List<NewFileInfo> files = fileExtractor.execute();
	 			files = linguistExtractor.setNotLinguist(files);
	 			if (filesInfo != null)
	 				applyFilterFiles(filesInfo.get(repositoryName), files);
	 			
	 			Map<String, LogCommitInfo> newCommits = filterCommitsByDate(commits, cal.getTime());
				DOACalculator doaCalculator = new DOACalculator(repositoryPath,	repositoryName, newCommits.values(), files);
				Repository repository = doaCalculator.execute();
				printCoverage(repository, devs, cal);
				
				cal.add(Calendar.MONTH, -1);
			}
		}

	private static void printTruckFactorDaily(String repositoryPath,
			String repositoryName, Map<String, List<LineInfo>> filesInfo,
			Map<String, List<LineInfo>> modulesInfo,
			FileInfoExtractor fileExtractor,
			LinguistExtractor linguistExtractor,
			GitLogExtractor gitLogExtractor, NewAliasHandler aliasHandler, String outputFile) throws Exception {
		
		// 全コミットを取得
		Map<String, LogCommitInfo> allCommits = gitLogExtractor.execute();
		if (aliasHandler != null)
			allCommits = aliasHandler.execute(repositoryName, allCommits);
		
		List<NewFileInfo> files = fileExtractor.execute();
		files = linguistExtractor.setNotLinguist(files);
		if(filesInfo != null && filesInfo.size()>0)
			if(filesInfo.containsKey(repositoryName))
				applyFilterFiles(filesInfo.get(repositoryName), files);
		
		if(modulesInfo != null && modulesInfo.containsKey(repositoryName))
			setModules(modulesInfo.get(repositoryName), files);
		
		// 最新日と最古日を取得
		Date latestDate = getLatestCommitDate(allCommits);
		Date earliestDate = getEarliestCommitDate(allCommits);
		
		if (latestDate == null || earliestDate == null) {
			LOGGER.warn("No valid commit dates found");
			return;
		}
		
		// 総日数を計算
		long totalDays = (latestDate.getTime() - earliestDate.getTime()) / (24 * 60 * 60 * 1000) + 1;
		LOGGER.info("Starting daily truck factor analysis from " + latestDate + " to " + earliestDate + " (" + totalDays + " days)");
		
		Calendar cal = Calendar.getInstance();
		cal.setTime(latestDate);
		
		SimpleDateFormat format = new SimpleDateFormat("yyyy/MM/dd");
		
		BufferedWriter writer = null;
		int currentDay = 0;
		int previousCommitCount = -1;
		TFInfo previousTFInfo = null;
		int tfCalculations = 0;
		int cacheHits = 0;
		
		try {
			if (outputFile != null) {
				writer = new BufferedWriter(new FileWriter(outputFile));
				LOGGER.info("Writing daily truck factor results to: " + outputFile);
				// CSVヘッダーを書き込み
				writer.write("Date,TF,Coverage");
				writer.newLine();
			}
			
			// 最新日から最古日まで日次で処理
			while (cal.getTime().after(earliestDate) || cal.getTime().equals(earliestDate)) {
				currentDay++;
				double progress = (double) currentDay / totalDays * 100;
				
				// 進捗を表示（--outputオプションが指定された時のみ）
				if (outputFile != null) {
					System.out.printf("\rProgress: %d/%d days (%.1f%%) - Processing: %s", 
						currentDay, totalDays, progress, format.format(cal.getTime()));
					System.out.flush(); // バッファをフラッシュして即座に表示
				}
				
				Map<String, LogCommitInfo> filteredCommits = filterCommitsByDate(allCommits, cal.getTime());
				int currentCommitCount = filteredCommits.size();
				
				TFInfo tfInfo;
				if (currentCommitCount == previousCommitCount && previousTFInfo != null) {
					// 前回と同じコミット数なので結果を再利用
					tfInfo = previousTFInfo;
					cacheHits++;
				} else {
					// コミット数が変わったので新たに計算
					if (!filteredCommits.isEmpty()) {
						DOACalculator doaCalculator = new DOACalculator(repositoryPath, repositoryName, 
							filteredCommits.values(), files);
						Repository repository = doaCalculator.execute();
						
						TruckFactor truckFactor = new PrunedGreedyTruckFactor(config.getMinPercentage());
						tfInfo = truckFactor.getTruckFactor(repository);
						
						previousTFInfo = tfInfo;
						previousCommitCount = currentCommitCount;
						tfCalculations++;
					} else {
						tfInfo = null;
					}
				}
				
				if (tfInfo != null) {
					if (writer != null) {
						// CSV形式でファイルに出力
						writer.write(String.format("%s,%d,%.2f", 
							format.format(cal.getTime()), tfInfo.getTf(), tfInfo.getCoverage() * 100));
						writer.newLine();
					} else {
						// コンソールにはCSV形式で出力
						System.out.printf("%s,%d,%.2f\n", 
							format.format(cal.getTime()), tfInfo.getTf(), tfInfo.getCoverage() * 100);
					}
				}
				
				cal.add(Calendar.DAY_OF_MONTH, -1);
			}
			
			if (outputFile != null) {
				System.out.println("\nProgress: Complete! Processed " + currentDay + " days.");
				System.out.println("Performance: " + tfCalculations + " TF calculations, " + cacheHits + " cache hits (" + 
					String.format("%.1f", (double)cacheHits/(tfCalculations + cacheHits)*100) + "% cache hit rate)");
			}
			
		} finally {
			if (writer != null) {
				writer.close();
				LOGGER.info("Daily truck factor analysis completed. Results saved to: " + outputFile);
			}
		}
	}

	private static Date getLatestCommitDate(Map<String, LogCommitInfo> commits) {
		Date latest = null;
		for (LogCommitInfo commit : commits.values()) {
			Date date = commit.getAuthorDate();
			if (date != null && (latest == null || date.after(latest))) {
				latest = date;
			}
		}
		return latest;
	}

	private static Date getEarliestCommitDate(Map<String, LogCommitInfo> commits) {
		Date earliest = null;
		for (LogCommitInfo commit : commits.values()) {
			Date date = commit.getAuthorDate();
			if (date != null && (earliest == null || date.before(earliest))) {
				earliest = date;
			}
		}
		return earliest;
	}
}
