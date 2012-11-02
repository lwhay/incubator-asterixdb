package edu.uci.ics.hivesterix.perf;

import java.io.File;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.Driver;
import org.junit.Test;

import edu.uci.ics.hivesterix.perf.base.AbstractPerfTestCase;
import edu.uci.ics.hivesterix.runtime.config.ConfUtil;

public class PerfTestSuiteCaseGenerator extends AbstractPerfTestCase {
    private File resultFile;
    private FileSystem dfs;

    PerfTestSuiteCaseGenerator(File queryFile, File resultFile) {
        super("testRuntimeFunction", queryFile);
        this.queryFile = queryFile;
        this.resultFile = resultFile;
    }

    @Test
    public void testRuntimeFunction() throws Exception {
        StringBuilder queryString = new StringBuilder();
        readFileToString(queryFile, queryString);
        String[] queries = queryString.toString().split(";");

        HiveConf hconf = ConfUtil.getHiveConf();
        Driver driver = new Driver(hconf);
        driver.init();

        dfs = FileSystem.get(ConfUtil.getJobConf());

        long startTime = System.currentTimeMillis();
        int i = 0;
        for (String query : queries) {
            if (i == queries.length - 1)
                break;
            driver.run(query);
            // driver.clear();
            i++;
        }
        long endTime = System.currentTimeMillis();
        System.out.println(resultFile.getName() + " execution time " + (endTime - startTime));

        String warehouse = hconf.get("hive.metastore.warehouse.dir");
        String tableName = removeExt(resultFile.getName());
        String directory = warehouse + "/" + tableName + "/";
        String localDirectory = "tmp";

        FileStatus[] files = dfs.listStatus(new Path(directory));
        FileSystem lfs = null;
        if (files == null) {
            lfs = FileSystem.getLocal(ConfUtil.getJobConf());
            files = lfs.listStatus(new Path(directory));
        }

        File resultDirectory = new File(localDirectory + "/" + tableName);
        deleteDir(resultDirectory);
        resultDirectory.mkdir();

        for (FileStatus fs : files) {
            Path src = fs.getPath();
            if (src.getName().indexOf("crc") >= 0)
                continue;

            String destStr = localDirectory + "/" + tableName + "/" + src.getName();
            Path dest = new Path(destStr);
            if (lfs != null) {
                lfs.copyToLocalFile(src, dest);
                dfs.copyFromLocalFile(dest, new Path(directory));
            } else
                dfs.copyToLocalFile(src, dest);
        }

        File[] rFiles = resultDirectory.listFiles();
        StringBuilder sb = new StringBuilder();
        for (File r : rFiles) {
            if (r.getName().indexOf("crc") >= 0)
                continue;
            readFileToString(r, sb);
        }
        deleteDir(resultDirectory);

        writeStringToFile(resultFile, sb);
    }

    private void deleteDir(File resultDirectory) {
        if (resultDirectory.exists()) {
            File[] rFiles = resultDirectory.listFiles();
            for (File r : rFiles)
                r.delete();
            resultDirectory.delete();
        }
    }
}
