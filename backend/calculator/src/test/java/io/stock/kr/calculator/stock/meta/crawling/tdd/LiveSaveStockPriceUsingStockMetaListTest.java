package io.stock.kr.calculator.stock.meta.crawling.tdd;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.DeleteTableRequest;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.util.TableUtils;
import io.stock.kr.calculator.stock.meta.crawling.tdd.dto.FSCStockPriceItem;
import io.stock.kr.calculator.stock.meta.repository.dynamo.StockMetaDocument;
import io.stock.kr.calculator.stock.meta.repository.dynamo.StockMetaRepository;
import io.stock.kr.calculator.stock.meta.crawling.StockMetaCrawlingDartService;
import io.stock.kr.calculator.stock.meta.crawling.dto.StockMetaDto;
import io.stock.kr.calculator.stock.meta.crawling.tdd.dto.FSCStockPriceResponse;
import io.stock.kr.calculator.stock.price.StockPriceDto;
import io.stock.kr.calculator.stock.price.redis.StockPriceRedisRepository;
import io.stock.kr.calculator.util.PagingUtil;
import lombok.Getter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 기능 완료 후 test-docker 에서만 동작하도록 테스트케이스 변경 및 세부 테스트 게이스 분리 예정
 */
@SpringBootTest
@ActiveProfiles("test-docker")
public class LiveSaveStockPriceUsingStockMetaListTest {
    @Autowired
    StockMetaRepository repository;

    @Autowired
    StockMetaCrawlingDartService service;

    @Autowired
    AmazonDynamoDB amazonDynamoDB;

    @Autowired
    DynamoDBMapper dynamoDBMapper;

    @Autowired
    RedisTemplate<String, StockPriceDto> stockPriceRedisTemplate;

    @Autowired
    RedisTemplate<String, String> fcsStockTickerListTemplate;

    @Autowired
    StockPriceRedisRepository stockPriceRedisRepository;

    ThreadPoolExecutor redisSaveExecutor = new ThreadPoolExecutor(1, 3, 2L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
    ThreadPoolExecutor apiExecutor = new ThreadPoolExecutor(1, 10, 2L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

    @BeforeEach
    public void beforeTest(){
//        deleteTableIfExist();
//        createTableRequest();
    }

    @AfterEach
    public void afterTest(){
//        deleteTableIfExist();
        if(!((ExecutorService) redisSaveExecutor).isShutdown()){
            redisSaveExecutor.shutdown();
        }
        if(!((ExecutorService)apiExecutor).isShutdown()){
            apiExecutor.shutdown();
        }

        stockPriceRedisRepository.deleteAll();

    }

    public void deleteTableIfExist(){
        DeleteTableRequest deleteTableRequest = dynamoDBMapper
                .generateDeleteTableRequest(StockMetaDocument.class, DynamoDBMapperConfig.DEFAULT);

        TableUtils.deleteTableIfExists(amazonDynamoDB, deleteTableRequest);
    }

    public void createTableRequest(){
        CreateTableRequest createTableRequest = dynamoDBMapper
                .generateCreateTableRequest(StockMetaDocument.class, DynamoDBMapperConfig.DEFAULT)
                .withProvisionedThroughput(new ProvisionedThroughput(1L, 1L));

        TableUtils.createTableIfNotExists(amazonDynamoDB, createTableRequest);
    }

    @Getter
    enum FSCParameters{
        NUM_OF_ROWS("numOfRows"),
        PAGE_NO("pageNo"),
        RESULT_TYPE("resultType"),
        BEGIN_BAS_DT("beginBasDt"),
        END_BAS_DT("endBasDt"),
        LIKE_SRTN_CD("likeSrtnCd"),
        SERVICE_KEY("serviceKey");

        private final String parameterName;

        FSCParameters(String parameterName){
            this.parameterName = parameterName;
        }

    }

    public FSCStockPriceResponse requestStockPrice(WebClient webClient, String serviceKey, String srtnCd, String beginBasDt, String endBasDt){
        return webClient.get()
                .uri(uriBuilder -> {
                    URI uri = uriBuilder
                            .path("/getStockPriceInfo")
                            .queryParam(FSCParameters.NUM_OF_ROWS.getParameterName(), "365")
                            .queryParam(FSCParameters.PAGE_NO.getParameterName(), "1")
                            .queryParam(FSCParameters.RESULT_TYPE.getParameterName(), "json")
                            .queryParam(FSCParameters.BEGIN_BAS_DT.getParameterName(), beginBasDt)
                            .queryParam(FSCParameters.END_BAS_DT.getParameterName(), endBasDt)
                            .queryParam(FSCParameters.LIKE_SRTN_CD.getParameterName(), srtnCd)
                            .queryParam(FSCParameters.SERVICE_KEY.getParameterName(), serviceKey)
                            .build();
                    try {
                        System.out.println(uri.toURL().toString());
                    } catch (MalformedURLException e) {
                        e.printStackTrace();
                    }
                    return uri;
                })
                .retrieve()
                .bodyToMono(FSCStockPriceResponse.class)
                .block();
    }

    public String fcsKey(String year, StockPriceDto stockPriceDto){
        StringBuilder builder = new StringBuilder();
        builder.append("FCS").append(year).append("-").append(stockPriceDto.getSrtnCd());
        return builder.toString();
    }

    @Test
    public void 종목리스트_조회_후_종가데이터를_불러온다_WEBCLIENT(){
        List<StockMetaDto> stockList = service.selectKrStockList("CORPCODE.xml");
        System.out.println(stockList.size());

        // serviceKey 는 공식페이지에서 제공하는 키가 2개 있다.
        // decoding 된 키 : API 통신 등에서 내부 통신에서 사용할 때 사용하는 키
        //  = 만약 라이브러리 내에서 내부적으로 encoding 을 기본적으로 수행하게끔 지원된다면, decoding 된 키를 지정해야 한다.
        //  = WebClient 의 경우는 통신시에 내부적으로(자동으로) URL Encoding 을 수행하기에 decoding 된 키를 사용하게끔 지정했다.
        //  (이걸로 하루 내내 원인을 못찾고 헤맴. 로그 자체도 단순히 SERVICE_KEY_NOT_REGISTERED 가 뜨기에 ServiceKey 가 잘못되었다고 판단해서 키를 다시 발급받는 등의 행동을 해야만 하게 된다.)
        // encoding 된 키 : 공식페이지에서 제공하는 SWAGGER 상에서 HTTP 통신을 할때에는 HTTP URL 형식에 맞게끔 base64 인코딩되어 있는 키
        final String serviceKey = "---";
        final String BASE_URL = "http://apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService";

        WebClient webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/101.0.4951.64 Safari/537.36 Edg/101.0.1210.47")
                .defaultHeader("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9")
                .defaultHeader("accept-encoding", "gzip, deflate, br")
                .defaultHeader("accept-language", "ko,en;q=0.9,en-US;q=0.8")
                .build();

        List<Future<FSCStockPriceResponse>> futureList = new ArrayList<>();

        subListAccept(stockList, (l)->{
            l.stream()
                .forEach(stockMetaDto -> {
                    CompletableFuture<FSCStockPriceResponse> future = CompletableFuture.supplyAsync(() -> {
                        long start = System.nanoTime();
                        FSCStockPriceResponse fscStockPriceResponse = requestStockPrice(webClient, serviceKey, stockMetaDto.getTicker(), "20220401", "20220430");
                        long diff = System.nanoTime() - start;
                        System.out.println("cost : " + (diff / 1000000) + " ms.");

                        if (fscStockPriceResponse.getResponse().getBody().getItems().getItem().size() == 0) return null;

                        List<StockPriceDto> list = fscStockPriceResponse.getResponse().getBody().getItems().getItem()
                                .stream()
                                .map(FSCStockPriceItem::toStockPriceDto)
                                .toList();

                        long redisStart = System.nanoTime();
                        stockPriceRedisTemplate.opsForList().leftPushAll(stockMetaDto.getTicker(), list);
                        fcsStockTickerListTemplate.opsForList().leftPush("2022", stockMetaDto.getTicker());
                        long redisDiff = System.nanoTime() - redisStart;

                        StringBuilder builder = new StringBuilder();
                        builder.append("redisCost : ").append((redisDiff / 1000000)).append(" ms. ( ").append(redisDiff).append(" ) nanoSecond.");
//                        System.out.println("redisCost : " + (redisDiff / 1000000) + " ms. ( " + redisDiff + " ) nanoSecond.");
                        System.out.println(builder.toString());

                        return fscStockPriceResponse;
                    }, apiExecutor);
                    futureList.add(future);
                });
        }, 1700);

        futureList.stream()
                    .forEach(fscStockPriceResponseFuture -> {
                        Optional.ofNullable(fscStockPriceResponseFuture)
                                .ifPresent(future->{
                                    try {
                                        FSCStockPriceResponse fscStockPriceResponse = fscStockPriceResponseFuture.get();
//                                        System.out.println("ticker = " + fcsStockTickerListTemplate.opsForList().size("2022"));
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    } catch (ExecutionException e) {
                                        e.printStackTrace();
                                    }
                                });
                    });

        System.out.println(">>>>>>");
//        System.out.println(dat.getResponse().getBody().getItems().getItem());
    }


    @Test
    public void TEMP(){
        fcsStockTickerListTemplate.opsForList().rightPush("2022", "AMZN-2022");
        fcsStockTickerListTemplate.opsForList().rightPush("2022", "AAPL-2022");

        fcsStockTickerListTemplate.opsForList().rightPush("2021", "AMZN-2021");

        System.out.println("2022 -->");
        System.out.println(fcsStockTickerListTemplate.opsForList().rightPop("2022")); // 가장 최근 순서로 pop
        System.out.println(fcsStockTickerListTemplate.opsForList().rightPop("2022"));
        System.out.println(fcsStockTickerListTemplate.opsForList().rightPop("2022"));
        System.out.println(fcsStockTickerListTemplate.opsForList().rightPop("2022"));

        System.out.println("2021 --> ");
        System.out.println(fcsStockTickerListTemplate.opsForList().rightPop("2021")); // 가장 최근 순서로 pop
    }

    public <T>  void subListAccept(List<T> stockList, Consumer<List<T>> consumer, int size){
        PagingUtil.PageUnit pageUnit = PagingUtil.pageUnit(stockList, size);
        PagingUtil.iterate(pageUnit, stockList, consumer);
    }

}
