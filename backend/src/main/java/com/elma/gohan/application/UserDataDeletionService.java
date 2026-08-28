package com.elma.gohan.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 删除一个匿名用户产生的全部个性化数据。 */
@Service
public class UserDataDeletionService {

    private final JdbcTemplate jdbc;

    public UserDataDeletionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 删除用户数据，但保留餐厅、风险结果和共享 Evidence。
     * 删除顺序遵循推荐会话的外键关系，重复调用不会报错。
     */
    @Transactional
    public void deleteAll(UUID anonymousUserId) {
        Object[] arguments = {anonymousUserId};
        jdbc.update("DELETE FROM restaurant_flavor_observation WHERE anonymous_user_id = ?", arguments);
        jdbc.update("DELETE FROM user_food_history WHERE anonymous_user_id = ?", arguments);
        jdbc.update("DELETE FROM user_behavior WHERE anonymous_user_id = ?", arguments);
        jdbc.update("DELETE FROM user_feedback WHERE anonymous_user_id = ?", arguments);
        jdbc.update("DELETE FROM user_taste_profile WHERE anonymous_user_id = ?", arguments);
        jdbc.update("DELETE FROM user_preference WHERE anonymous_user_id = ?", arguments);
        jdbc.update("DELETE FROM recommendation_log WHERE anonymous_user_id = ?", arguments);
    }
}
