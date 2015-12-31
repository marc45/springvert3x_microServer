package com.solodream.spring.vertx.vertx;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.hazelcast.util.MD5Util;
import com.solodream.spring.vertx.common.DateUtil;
import com.solodream.spring.vertx.jpa.domain.ClientAccountInfoDto;
import com.solodream.spring.vertx.req.JsonReq;
import com.solodream.spring.vertx.req.client.SmsRequestParam;
import com.solodream.spring.vertx.req.client.TokenRequestParam;
import com.solodream.spring.vertx.resp.BaseResp;
import com.solodream.spring.vertx.resp.login.LoginResponse;
import com.solodream.spring.vertx.resp.login.SmsResponse;
import com.solodream.spring.vertx.service.RedisCacheService;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.JWTAuthHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class HttpServerVerticle extends AbstractVerticle {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerVerticle.class);

    @Autowired
    private RedisCacheService redisCacheService;


    public void start() {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        // Create a JWT Auth Provider
        JWTAuth jwt = JWTAuth.create(vertx, new JsonObject()
                .put("keyStore", new JsonObject()
                        .put("type", "jceks")
                        .put("path", "keystore.jceks")
                        .put("password", "secret")));

        // protect the API
        router.route("/api/*").handler(JWTAuthHandler.create(jwt, "/api/newToken"));

        // this route is excluded from the auth handler
        router.get("/api/newToken").handler(ctx -> {
            ctx.response().putHeader("Content-Type", "text/plain");
            String generateToken = jwt.generateToken(new JsonObject(), new JWTOptions().setExpiresInSeconds(1 * 60));
            ctx.response().end(generateToken);
            redisCacheService.put("token", generateToken, 1 * 60);
        });
//        router.route().handler(CookieHandler.create());
//        router.route().handler(SessionHandler.create(LocalSessionStore.create(vertx)));
//        router.route().handler(BodyHandler.create());
//        router.route("/*").handler(SoloAuthProvider.create(vertx,redisCacheService));
//
//        router.route("/*").handler(req -> {
//           LOGGER.info("Any requests to URI starting '/' require login");
//            // No auth required
//            req.next();
//        });

        router.route("/login").handler(
                req -> {
                    JsonObject jsonString = req.getBodyAsJson();
                    LOGGER.info("Received a http request,enter into /client/login");
                    BaseResp<LoginResponse> response = new BaseResp<LoginResponse>();
                    LoginResponse resp = new LoginResponse();
                    vertx.eventBus().send("login", jsonString, ar -> {
                        if (ar.succeeded()) {
                            String dto = (String) ar.result().body();
//                            ReqHandle.setOperator(req.session(), (String) ar.result().body());
                            LOGGER.info("Received reply: " + ar.result().body());
                            ClientAccountInfoDto accountInfoDto = JSON.parseObject(dto, new TypeReference<ClientAccountInfoDto>() {
                            });
                            Date date = new Date();
                            String accessTokenExpire = DateUtil.formatDateTime(DateUtil.addDateMinu(date, 10080));
                            String refreshTokenExpire = DateUtil.formatDateTime(DateUtil.addDateMinu(date, 20160));
                            req.response().putHeader("Content-Type", "text/plain");
                            String generateToken = jwt.generateToken(new JsonObject(), new JWTOptions().setSubject(accountInfoDto.getAccount()).setAlgorithm("HS256").setExpiresInSeconds(10080 * 60));
                            //save one week
                            redisCacheService.put("token", generateToken, 10080 * 60);
                            //save two week
                            String refreshToken = MD5Util.toMD5String(generateToken);
                            redisCacheService.put(refreshToken, generateToken, 20160 * 60);
                            resp.setAccessToken(generateToken);
                            resp.setRefreshToken(refreshToken);
                            resp.setAccessTokenExpire(accessTokenExpire);
                            resp.setRefreshTokenExpire(refreshTokenExpire);
                            resp.setCompanyId(accountInfoDto.getCompanyId().toString());
                            resp.setCompanyName(accountInfoDto.getCompanyName());
                            resp.setCustomerName(accountInfoDto.getCustomerName());
                            resp.setCustomerId(accountInfoDto.getCustomerId().toString());
//                            resp.setContractId(accountInfoDto.get);
                            response.setData(resp);
                            req.response().end(JSON.toJSONString(response));

                        } else {
                            resp.setCode(1201);
                            resp.setMsg("this username or password is wrong");
                            req.response().end(JSON.toJSONString(response));
                        }
                    });
                });

        router.post("/getAccessToken").handler(ctx -> {
            LOGGER.info("Received a http request,enter into /client/getAccessToken");
            JsonObject jsonString = ctx.getBodyAsJson();
            JsonReq<TokenRequestParam> obj = JSON.parseObject(jsonString.toString(), new TypeReference<JsonReq<TokenRequestParam>>() {
            });
            String refreshToken = obj.getParam().getRefreshToken();
            ctx.response().putHeader("Content-Type", "text/plain");
            String token = redisCacheService.get(refreshToken);

            ctx.response().end(token);
        });

        router.route("/getSmsCode").handler(
                req -> {
                    LOGGER.info("Received a http request,enter into /client/getSmsCode");
                    JsonObject jsonString = req.getBodyAsJson();
                    JsonReq<SmsRequestParam> obj = JSON.parseObject(jsonString.toString(), new TypeReference<JsonReq<SmsRequestParam>>() {
                    });
                    String mobile = obj.getParam().getMobile();
                    BaseResp<SmsResponse> response = new BaseResp<SmsResponse>();
                    SmsResponse resp = new SmsResponse();
                    vertx.eventBus().send("sms", mobile, ar -> {
                        if (ar.succeeded()) {
                            response.setData(resp);
                            LOGGER.info("Received reply: " + ar.result().body());
                            req.response().end(JSON.toJSONString(response));
                        }

                    });
                });

        router.route("/client/getLastVersion").handler(
                req -> {
                    LOGGER.info("Received a http request to /client/getLastVersion");
                    JsonObject product = req.getBodyAsJson();
                    String version = "";
                    vertx.eventBus().send("version", product, ar -> {
                        if (ar.succeeded()) {
                            LOGGER.info("Received reply: " + ar.result().body());
                            req.response().end((String) ar.result().body());
                        }
                    });
                });

        router.route("/client/logout").handler(
                req -> {
                    LOGGER.info("Received a http request to /client/logout");
                    JsonObject device = req.getBodyAsJson();
                    vertx.eventBus().send("device", device, ar -> {
                        if (ar.succeeded()) {
                            LOGGER.info("Received reply: " + ar.result().body());
                            req.response().end((String) ar.result().body());
                        }
                    });
                });


        router.route("/client/getDeviceId").handler(
                req -> {
                    LOGGER.info("Received a http request to /client/getDeviceId");
                    JsonObject device = req.getBodyAsJson();
                    vertx.eventBus().send("device", device, ar -> {
                        if (ar.succeeded()) {
                            LOGGER.info("Received reply: " + ar.result().body());
                            req.response().end((String) ar.result().body());
                        }
                    });
                });


        router.route("/token").handler(
                req -> {
                    LOGGER.info("Received a http request");
                    String version = req.request().getParam("token");
                    vertx.eventBus().send("version", version, ar -> {
                        if (ar.succeeded()) {
                            LOGGER.info("Received reply: " + ar.result().body());
                            req.response().end((String) ar.result().body());
                        }
                    });
                });

        vertx.createHttpServer().requestHandler(router::accept).listen(18080);
        LOGGER.info("Started HttpServer(port=18080).");
    }
}