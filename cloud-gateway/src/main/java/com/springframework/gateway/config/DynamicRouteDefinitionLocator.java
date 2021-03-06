package com.springframework.gateway.config;

import com.springframework.gateway.constant.CommonConstant;
import com.springframework.gateway.domain.routeconfig.entity.RouteConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionRepository;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.data.redis.core.RedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Collections.synchronizedMap;

/**
 * @author summer  基于redis方式做动态路由
 * 2018/7/3
 */
@Slf4j
public class DynamicRouteDefinitionLocator implements RouteDefinitionRepository {
    private RedisTemplate redis;
    private final Map<String, RouteDefinition> routes = synchronizedMap(new LinkedHashMap<String, RouteDefinition>());


    DynamicRouteDefinitionLocator(RedisTemplate redisTemplate) {
        this.redis = redisTemplate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Mono<Void> save(Mono<RouteDefinition> route) {
        RouteConfig routeConfig = new RouteConfig();
        routeConfig.setRouteId(route.block().getId());

        return route.flatMap(r -> {
            redis.opsForHash().put(CommonConstant.ROUTE_KEY, r.getId(), r);
            return Mono.empty();
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Mono<Void> delete(Mono<String> routeId) {
        return routeId.flatMap(id -> {
            if (0 != redis.opsForHash().delete(CommonConstant.ROUTE_KEY, id)) {
                return Mono.empty();
            }
            return Mono.defer(() -> Mono.error(new NotFoundException("RouteDefinition not found: " + routeId)));
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.fromIterable(redis.opsForHash().entries(CommonConstant.ROUTE_KEY).values());
    }
}
