package com.dianping.puma.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dianping.lion.client.ConfigCache;
import com.dianping.lion.client.LionException;
@Service("invervalConfig")
public class TaskInvervalConfig {

	private static final Logger LOG = LoggerFactory.getLogger(TaskInvervalConfig.class);
	
	private static final String SEQ_INTERVAL_NAME="puma.server.interval.seq";
	
	private static final String CLIENTIP_INTERVAL_NAME="puma.server.interval.ip";
	
	private static final String SERVERINFO_INTERVAL_NAME="puma.server.interval.serverInfo";
	
	private String seqInterval;
	private String clientIpInterval;
	private String serverInfoInterval;

	public TaskInvervalConfig(){
		seqInterval = "0/"+getInterval(SEQ_INTERVAL_NAME)+" * * * * ?";
		clientIpInterval = "0/"+getInterval(CLIENTIP_INTERVAL_NAME)+" * * * * ?";
		serverInfoInterval = "0/"+getInterval(SERVERINFO_INTERVAL_NAME)+" * * * * ?";
	}
	
	protected long getInterval(String intervalName) {
		long interval = 60000;
		try {
			Long temp = ConfigCache.getInstance().getLongProperty(intervalName);
			if (temp != null) {
				interval = temp.longValue();
			}
		} catch (LionException e) {
			LOG.error(e.getMessage(), e);
		}
		return interval;
	}

	public void setSeqInterval(String seqInterval) {
		this.seqInterval = seqInterval;
	}

	public String getSeqInterval() {
		return seqInterval;
	}

	public void setClientIpInterval(String clientIpInterval) {
		this.clientIpInterval = clientIpInterval;
	}

	public String getClientIpInterval() {
		return clientIpInterval;
	}

	public void setServerInfoInterval(String serverInfoInterval) {
		this.serverInfoInterval = serverInfoInterval;
	}

	public String getServerInfoInterval() {
		return serverInfoInterval;
	}
	
}