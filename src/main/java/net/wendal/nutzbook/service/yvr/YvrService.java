package net.wendal.nutzbook.service.yvr;

import static net.wendal.nutzbook.bean.CResult._fail;
import static net.wendal.nutzbook.bean.CResult._ok;
import static net.wendal.nutzbook.util.RedisInterceptor.jedis;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringEscapeUtils;
import org.nutz.aop.interceptor.async.Async;
import org.nutz.dao.Cnd;
import org.nutz.dao.Dao;
import org.nutz.dao.Sqls;
import org.nutz.dao.pager.Pager;
import org.nutz.dao.sql.Sql;
import org.nutz.ioc.aop.Aop;
import org.nutz.ioc.loader.annotation.Inject;
import org.nutz.ioc.loader.annotation.IocBean;
import org.nutz.lang.Files;
import org.nutz.lang.Strings;
import org.nutz.lang.random.R;
import org.nutz.lang.util.NutMap;
import org.nutz.log.Log;
import org.nutz.log.Logs;
import org.nutz.mvc.Mvcs;
import org.nutz.mvc.annotation.Param;
import org.nutz.mvc.upload.TempFile;

import net.wendal.nutzbook.bean.CResult;
import net.wendal.nutzbook.bean.User;
import net.wendal.nutzbook.bean.UserProfile;
import net.wendal.nutzbook.bean.yvr.Topic;
import net.wendal.nutzbook.bean.yvr.TopicReply;
import net.wendal.nutzbook.bean.yvr.TopicTag;
import net.wendal.nutzbook.bean.yvr.TopicType;
import net.wendal.nutzbook.service.BigContentService;
import net.wendal.nutzbook.service.PushService;
import net.wendal.nutzbook.util.RedisKey;
import net.wendal.nutzbook.util.Toolkit;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

@IocBean(create="init")
public class YvrService implements RedisKey {
	
	private static final Log log = Logs.get();

	@Inject 
	protected Dao dao;
	
	@Inject
	protected TopicSearchService topicSearchService;
	
	@Inject
	protected PushService pushService;
	
	@Inject
	protected BigContentService bigContentService;

	@Inject("java:$conf.get('topic.image.dir')")
	protected String imageDir;
	
	@Inject("java:$conf.get('topic.global.watchers')")
	protected String topicGlobalWatchers;
	
	protected Set<Integer> globalWatcherIds = new HashSet<>();
	
	@Aop("redis")
	public void fillTopic(Topic topic, Map<Integer, UserProfile> authors) {
		if (topic.getUserId() == 0)
			topic.setUserId(1);
		topic.setAuthor(_cacheFetch(authors, topic.getUserId()));
		Double reply_count = jedis().zscore(RKEY_REPLY_COUNT, topic.getId());
		topic.setReplyCount(reply_count == null ? 0 : reply_count.intValue());
		if (topic.getReplyCount() > 0) {
			String replyId = jedis().hget(RKEY_REPLY_LAST, topic.getId());
			TopicReply reply = dao.fetch(TopicReply.class, replyId);
			if (reply != null) {
				if (reply.getUserId() == 0)
					reply.setUserId(1);
				reply.setAuthor(_cacheFetch(authors, reply.getUserId()));
				topic.setLastComment(reply);
			}
		}
		Double visited = jedis().zscore(RKEY_TOPIC_VISIT, topic.getId());
		topic.setVisitCount((visited == null) ? 0 : visited.intValue());
	}
	
	protected UserProfile _cacheFetch(Map<Integer, UserProfile> authors, int userId) {
		if (authors == null)
			return null;
		UserProfile author = authors.get(userId);
		if (author == null) {
			author = dao.fetch(UserProfile.class, userId);
			authors.put(userId, author);
		}
		return author;
	}
	
	@Aop("redis")
	public String accessToken(String loginname) {
		return jedis().hget(RKEY_USER_ACCESSTOKEN, loginname);
	}
	
	@Aop("redis")
	public String accessToken(UserProfile profile) {
		String loginname = profile.getLoginname();
		String at = jedis().hget(RKEY_USER_ACCESSTOKEN, loginname);
		if (at == null) {
			// 双向绑定
			at = R.UU32();
			jedis().hset(RKEY_USER_ACCESSTOKEN, loginname, at);
			jedis().hset(RKEY_USER_ACCESSTOKEN2, at, loginname);
			jedis().hset(RKEY_USER_ACCESSTOKEN3, at, ""+profile.getUserId());
		}
		return at;
	}
	
	@Aop("redis")
	public void resetAccessToken(String loginname) {
		String at = jedis().hget(RKEY_USER_ACCESSTOKEN, loginname); {
			jedis().hdel(RKEY_USER_ACCESSTOKEN, loginname);
			jedis().hdel(RKEY_USER_ACCESSTOKEN2, at);
			jedis().hdel(RKEY_USER_ACCESSTOKEN3, at);
		}
	}
	
	@Aop("redis")
	public int getUserByAccessToken(String at) {
		String uid_str = jedis().hget(RKEY_USER_ACCESSTOKEN3, at);
		if (uid_str == null)
			return -1;
		return Integer.parseInt(uid_str);
	}

	@Aop("redis")
	public CResult add(Topic topic, int userId) {
		if (userId < 1) {
			return _fail("请先登录");
		}
		if (Strings.isBlank(topic.getTitle()) || topic.getTitle().length() > 1024 || topic.getTitle().length() < 5) {
			return _fail("标题长度不合法");
		}
		if (Strings.isBlank(topic.getContent())) {
			return _fail("内容不合法");
		}
		if (topic.getTags() != null && topic.getTags().size() > 10) {
			return _fail("最多只能有10个tag");
		}
		if (0 != dao.count(Topic.class, Cnd.where("title", "=", topic.getTitle().trim()))) {
			return _fail("相同标题已经发过了");
		}
		topic.setTitle(Strings.escapeHtml(topic.getTitle().trim()));
		topic.setUserId(userId);
		topic.setTop(false);
		topic.setTags(new HashSet<String>());
		if (topic.getType() == null)
			topic.setType(TopicType.ask);
		topic.setContent(Toolkit.filteContent(topic.getContent()));
		String oldContent = topic.getContent();
		topic.setContentId(bigContentService.put(topic.getContent()));
		topic.setContent(null);
		dao.insert(topic);
		try {
			topic.setContent(oldContent);
			topicSearchService.add(topic);
		} catch (Exception e) {
		}
		// 如果是ask类型,把帖子加入到 "未回复"列表
		Pipeline pipe = jedis().pipelined();
		if (TopicType.ask.equals(topic.getType())) {
			pipe.zadd(RKEY_TOPIC_NOREPLY, System.currentTimeMillis(), topic.getId());
		}
		pipe.zadd(RKEY_TOPIC_UPDATE + topic.getType(), System.currentTimeMillis(), topic.getId());
		if (topic.getType() == TopicType.ask || topic.getType() == TopicType.news) {
			pipe.zadd(RKEY_TOPIC_UPDATE_ALL, System.currentTimeMillis(), topic.getId());
			pipe.zincrby(RKEY_USER_SCORE, 100, ""+userId);
			String replyAuthorName = dao.fetch(User.class, userId).getName();
			for (Integer watcherId : globalWatcherIds) {
			    if (watcherId != userId)
				pushUser(watcherId,
						"新帖:" + StringEscapeUtils.unescapeHtml(topic.getTitle()),
						topic.getId(),
						replyAuthorName,
						topic.getTitle(),
						PushService.PUSH_TYPE_REPLY);
			}
		}
        pipe.sync();
		updateTopicTypeCount();
		return _ok(topic.getId());
	}

	@Aop("redis")
	public CResult addReply(final String topicId, final TopicReply reply, final int userId) {
		if (userId < 1)
			return _fail("请先登录");
		if (reply == null || reply.getContent() == null || reply.getContent().trim().isEmpty()) {
			return _fail("内容不能为空");
		}
		final String cnt = reply.getContent().trim();
		final Topic topic = dao.fetch(Topic.class, topicId); // TODO 改成只fetch出type属性
		if (topic == null) {
			return _fail("主题不存在");
		}
		if (topic.isLock()) {
			return _fail("该帖子已经锁定,不能回复");
		}
		reply.setTopicId(topicId);
		reply.setUserId(userId);
		reply.setContent(Toolkit.filteContent(reply.getContent()));
		reply.setContentId(bigContentService.put(reply.getContent()));
		reply.setContent(null);
		dao.insert(reply);
		// 更新索引
		topicSearchService.add(topic);
		// 更新topic的时间戳
		Pipeline pipe = jedis().pipelined();
		if (topic.isTop()) {
			pipe.zadd(RKEY_TOPIC_TOP, reply.getCreateTime().getTime(), topicId);
		} else {
			pipe.zadd(RKEY_TOPIC_UPDATE + topic.getType(), reply.getCreateTime().getTime(), topicId);
			if (topic.getType() != TopicType.nb && topic.getType() != TopicType.shortit)
			    pipe.zadd(RKEY_TOPIC_UPDATE_ALL, reply.getCreateTime().getTime(), topicId);
		}
		pipe.zrem(RKEY_TOPIC_NOREPLY, topicId);
		if (topic.getTags() != null) {
			for (String tag : topic.getTags()) {
				pipe.zadd(RKEY_TOPIC_TAG+tag.toLowerCase().trim(), reply.getCreateTime().getTime(), topicId);
			}
		}
		pipe.hset(RKEY_REPLY_LAST, topicId, reply.getId());
		pipe.zincrby(RKEY_REPLY_COUNT, 1, topicId);
		pipe.zincrby(RKEY_USER_SCORE, 10, ""+userId);
		pipe.sync();
		
		notifyUsers(topic, reply, cnt, userId);
		
		return _ok(reply.getId());
	}
	
	@Async
	protected void notifyUsers(Topic topic, TopicReply reply, String cnt, int userId) {
	    String replyAuthorName = dao.fetch(User.class, userId).getName();
        // 通知原本的作者
        if (topic.getUserId() != userId) {
            String alert = replyAuthorName + "回复了您的帖子";
            pushUser(topic.getUserId(),
                    alert,
                    topic.getId(),
                    replyAuthorName,
                    topic.getTitle(),
                    PushService.PUSH_TYPE_REPLY);
        }

        Set<String> ats = findAt(cnt, 5);
        for (String at : ats) {
            User user = dao.fetch(User.class, at);
            if (user == null)
                continue;
            if (topic.getUserId() == user.getId())
                continue; // 前面已经发过了
            if (userId == user.getId())
                continue; // 自己@自己, 忽略
            String alert = replyAuthorName + "在帖子回复中@了你";
            pushUser(user.getId(),
                    alert,
                    topic.getId(),
                    replyAuthorName,
                    topic.getTitle(),
                    PushService.PUSH_TYPE_AT);
        }
	}
	
	@Aop("redis")
	public CResult replyUp(String replyId, int userId) {
		if (userId < 1)
			return _fail("你还没登录呢");
		if (1 != dao.count(TopicReply.class, Cnd.where("id", "=", replyId))) {
			return _fail("没这条评论");
		}
		String key = RKEY_REPLY_LIKE + replyId;
		Double t = jedis().zscore(key, "" + userId);
		if (t != null) {
			jedis().zrem(key, userId + "");
			return _ok("down");
		} else {
			jedis().zadd(key, System.currentTimeMillis(), userId + "");
			return _ok("up");
		}
	}
	
	@Async
	protected void pushUser(int userId, String alert, String topic_id, String post_user, String topic_title, int type) {
	    topic_title = StringEscapeUtils.unescapeHtml(topic_title);
		Map<String, String> extras = new HashMap<String, String>();
		extras.put("topic_id", topic_id);
		extras.put("post_user", post_user);
		extras.put("topic_title", topic_title);
		// 通知类型
		extras.put("type", type+"");
		pushService.alert(userId, alert, topic_title, extras);
	}
	
	public List<Topic> getRecentTopics(int userId, Pager pager) {
		List<Topic> recent_topics = dao.query(Topic.class, Cnd.where("userId", "=", userId).desc("createTime"), pager);

		Map<Integer, UserProfile> authors = new HashMap<Integer, UserProfile>();
		if (!recent_topics.isEmpty()) {
			for (Topic topic : recent_topics) {
				fillTopic(topic, authors);
			}
		}
		pager.setRecordCount(dao.count(Topic.class, Cnd.where("userId", "=", userId)));
		return recent_topics;
		
	}
	
	public List<Topic> getRecentReplyTopics(int userId, Pager pager) {

		Map<Integer, UserProfile> authors = new HashMap<Integer, UserProfile>();
		Cnd cnd = Cnd.where("userId", "=", userId);
		cnd.desc("createTime");
		
		Sql sql = Sqls.queryString("select DISTINCT topicId from t_topic_reply $cnd").setEntity(dao.getEntity(TopicReply.class)).setVar("cnd", cnd);
		pager.setRecordCount(dao.execute(Sqls.fetchInt("select count(DISTINCT topicId) from t_topic_reply $cnd").setEntity(dao.getEntity(TopicReply.class)).setVar("cnd", cnd)).getInt());
		sql.setPager(pager);
		String[] replies_topic_ids = dao.execute(sql).getObject(String[].class);
		List<Topic> recent_replies = new ArrayList<Topic>();
		for (String topic_id : replies_topic_ids) {
			Topic _topic = dao.fetch(Topic.class, topic_id);
			if (_topic == null)
				continue;
			recent_replies.add(_topic);
		}
		if (!recent_replies.isEmpty()) {
			for (Topic topic : recent_replies) {
				fillTopic(topic, authors);
			}
		}
		return recent_replies;
	}
	
	@Aop("redis")
	public int getUserScore(int userId) {
		Double score = jedis().zscore(RKEY_USER_SCORE, ""+userId);
		if (score == null) {
			return 0;
		} else {
			return score.intValue();
		}
	}
	
	public NutMap upload(TempFile tmp, int userId) throws IOException {
		NutMap re = new NutMap();
		if (userId < 1)
			return re.setv("msg", "请先登陆!");
		if (tmp == null || tmp.getSize() == 0) {
			return re.setv("msg", "空文件");
		}
		if (tmp.getSize() > 10 * 1024 * 1024) {
			tmp.delete();
			return re.setv("msg", "文件太大了");
		}
		String id = R.UU32();
		String path = "/" + id.substring(0, 2) + "/" + id.substring(2);
		File f = new File(imageDir + path);
		Files.createNewFile(f);
		Files.write(f, tmp.getInputStream());
		tmp.delete();
		re.put("url", Mvcs.getServletContext().getContextPath()+"/yvr/upload" + path);
		re.setv("success", true);
		return re;
	}
	
	public void init() {
		if (topicGlobalWatchers != null) {
			for (String username : Strings.splitIgnoreBlank(topicGlobalWatchers)) {
				User user = dao.fetch(User.class, username);
				if (user == null) {
					log.infof("no such user[name=%s] for topic watch", username);
					continue;
				}
				globalWatcherIds.add(user.getId());
			}
		}
	}
	
	static Pattern atPattern = Pattern.compile("@([a-zA-Z0-9\\_]{4,20}\\s)");
	
	public static Set<String> findAt(String cnt, int limit) {
		Set<String> ats = new HashSet<String>();
		Matcher matcher = atPattern.matcher(cnt+" ");
		int start = 0;
		int end = 0;
		while (end < cnt.length() && matcher.find(end)) {
			start = matcher.start();
			end = matcher.end();
			ats.add(cnt.substring(start+1, end-1).trim().toLowerCase());
			if (limit <= ats.size())
				break;
		}
		return ats;
	}
	

	@Aop("redis")
	public boolean updateTags(String topicId, @Param("tags")Set<String> tags) {
		if (Strings.isBlank(topicId) || tags == null) {
			return false;
		}
		Topic topic = dao.fetch(Topic.class, topicId);
		if (topic == null)
			return false;
		Set<String> oldTags = topic.getTags();
		if (oldTags == null)
			oldTags = new HashSet<>();
		log.debugf("update from '%s' to '%s'", oldTags, tags);
		topic.setTags(tags);
		dao.update(topic, "tags");
		Set<String> newTags = new HashSet<>(tags);
		newTags.removeAll(oldTags);
		Set<String> removeTags = new HashSet<>(oldTags);;
		removeTags.remove(tags);
		fillTopic(topic, null);
		Date lastReplyTime = topic.getCreateTime();
		if (topic.getLastComment() != null)
			lastReplyTime = topic.getLastComment().getCreateTime();
		Pipeline pipe = jedis().pipelined();
		for (String tag : removeTags) {
			pipe.zrem(RKEY_TOPIC_TAG+tag.toLowerCase().trim(), topic.getId());
			pipe.zincrby(RKEY_TOPIC_TAG_COUNT, -1, tag.toLowerCase().trim());
		}
		for (String tag : newTags) {
			pipe.zadd(RKEY_TOPIC_TAG+tag.toLowerCase().trim(), lastReplyTime.getTime(), topic.getId());
			pipe.zincrby(RKEY_TOPIC_TAG_COUNT, 1, tag.toLowerCase().trim());
		}
		pipe.sync();
		return true;
	}
	
	@Aop("redis")
	public List<Topic> fetchTop() {
		List<Topic> list = new ArrayList<>();
		Map<Integer, UserProfile> authors = new HashMap<>();
		for(String id :jedis().zrevrangeByScore(RKEY_TOPIC_TOP, Long.MAX_VALUE, 0)) {
			Topic topic = dao.fetch(Topic.class, id);
			if (topic == null)
				continue;
			fillTopic(topic, authors);
			list.add(topic);
		}
		return list;
	}
	
	@Aop("redis")
	public List<TopicTag> fetchTopTags() {
		Set<String> names = jedis().zrevrangeByScore(RKEY_TOPIC_TAG_COUNT, Long.MAX_VALUE, 0, 0, 20);
		List<TopicTag> tags = new ArrayList<>();
		List<Response<Double>> tmps = new ArrayList<>();
		Pipeline pipe = jedis().pipelined();
		for (String name: names) {
			tmps.add(pipe.zscore(RKEY_TOPIC_TAG_COUNT, name));
			tags.add(new TopicTag(name, 0));
		}
		pipe.sync();
		Iterator<TopicTag> it = tags.iterator();
		for (Response<Double> response : tmps) {
			it.next().setCount(response.get().intValue());
		}
		return tags;
	}
	
	@Aop("redis")
	public boolean checkNonce(String nonce, String time) {
		try {
			long t = Long.parseLong(time);
			if (System.currentTimeMillis() - t > 10*60*1000) {
				return false;
			}
			String key = "at:nonce:"+nonce;
			Long re = jedis().setnx(key, "");
			if (re == 0) {
				return false;
			}
			jedis().expire(key, 10*60);
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}
	
	@Async
	@Aop("redis")
	public void updateTopicTypeCount() {
		for (TopicType tt : TopicType.values()) {
			tt.count = jedis().zcount(RKEY_TOPIC_UPDATE+tt.name(), "-inf", "+inf");
		}
	}

    @Aop("redis")
	public Object check(String topicId, int replies) {
        Topic topic = dao.fetch(Topic.class, topicId);
        if (topic == null)
            return "";
        Double reply_count = jedis().zscore(RKEY_REPLY_COUNT, topicId);
        if (reply_count == null)
            reply_count = Double.valueOf(0);
        if (reply_count.intValue() == replies) {
            return "";
        }
        String replyId = jedis().hget(RKEY_REPLY_LAST, topicId);
        TopicReply reply = dao.fetch(TopicReply.class, replyId);
        dao.fetchLinks(reply, null);

        NutMap re = new NutMap().setv("count", reply_count.intValue());
        re.put("data", reply.getAuthor().getNickname() + " 回复了帖子:" + topic.getTitle());
        re.put("options", new NutMap().setv("tag", topicId));
        return re;
    }
}
