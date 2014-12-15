package com.alibaba.datax.plugin.reader.oceanbasereader.utils;

import com.alibaba.datax.plugin.rdbms.util.DBUtil;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.plugin.reader.oceanbasereader.Key;
import com.alipay.oceanbase.OceanbaseDataSourceProxy;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.Map;

public final class OBDataSource {
	private static final Logger log = LoggerFactory.getLogger(OBDataSource.class);
	
	private static final Map<String,DataSourceHolder> datasources = Maps.newHashMap();

	public static synchronized void init(Configuration configuration) throws Exception {
        final String url = configuration.getString(Key.CONFIG_URL);
		if (datasources.containsKey(url)){
            datasources.get(url).increaseReference();
        }else{
            DataSourceHolder holder = new DataSourceHolder(url);
            datasources.put(url,holder);
            log.info(String.format("init datasource success [%s]",url));
        }
	}

	public static synchronized void destroy(Configuration configuration) throws Exception {
        String url = configuration.getString(Key.CONFIG_URL);
        Preconditions.checkState(datasources.containsKey(url),"datasource for [%s] not exist",url);
        DataSourceHolder holder = datasources.get(url);
        holder.decreaseReference();
        if(holder.canClose()) {
            datasources.remove(url);
            holder.close();
            log.info(String.format("close datasource success [%s]",url));
        }
	}

	public static <T> T execute(String url, String sql, int timeout, ResultSetHandler<T> handler) throws Exception {
		int retry = 0;
		while(retry++ <= 3){
			Connection connection = null;
			Statement statement = null;
			ResultSet result = null;
			try {
                DataSourceHolder holder = datasources.get(url);
                Preconditions.checkState(holder != null,"can't fetch [%s] datasource",url);
				connection = holder.datasource.getConnection();
				statement = connection.createStatement();
				log.debug("datax set ob_query_timeout to {} us", timeout);
				statement.execute(String.format("set ob_query_timeout = %s", timeout));
				statement.close();
				statement = connection.createStatement();
				log.debug("start execute {}", sql);
				result = statement.executeQuery(sql);
				return handler.callback(result);
			} catch(SQLException e){
				log.error(String.format("execute sql [%s] exception retry", sql), e);
			}finally {
				DBUtil.closeDBResources(result, statement, connection);
			}
		}
		throw new Exception(String.format("retry sql [%s] fail exit", sql));
	}

    private static class DataSourceHolder {
        private int reference;
        private final DataSource datasource;

        public DataSourceHolder(final String url) throws Exception{
            this.reference = 1;
            this.datasource = new OceanbaseDataSourceProxy(){
                {
                    this.setConfigURL(url);
                    this.init();
                }

                public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
                    return null;//for > JDK6 compile
                }
            };
        }

        public void increaseReference(){
            this.reference ++;
        }

        public void decreaseReference(){
            this.reference --;
        }

        public boolean canClose(){
            return reference == 0;
        }

        public void close() throws Exception{
            if(this.canClose())
                ((OceanbaseDataSourceProxy) datasource).destroy();
        }
    }
}
