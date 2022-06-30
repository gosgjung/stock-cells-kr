package io.stock.evaluation.reactive_data.ticker.meta.cache;

import io.stock.evaluation.reactive_data.ticker.meta.dto.TickerMetaItem;
import io.stock.evaluation.reactive_data.ticker.meta.external.DartDataConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Range;
import org.springframework.data.domain.Range.Bound;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class TickerMetaRedisService {
    private final ReactiveRedisOperations<String, String> tickerMetaAutoCompleteRedisOps;
    private final ReactiveRedisOperations<String, TickerMetaItem> tickerMetaMapOps;
    private final Pattern pattern = Pattern.compile(".*\\§§§");

    public TickerMetaRedisService(
        @Qualifier("tickerAutoCompleteRedisOperation")
        ReactiveRedisOperations<String, String> tickerMetaAutoCompleteRedisOps,
        ReactiveRedisOperations<String, TickerMetaItem> tickerMetaMapOps
    ){
        this.tickerMetaAutoCompleteRedisOps = tickerMetaAutoCompleteRedisOps;
        this.tickerMetaMapOps = tickerMetaMapOps;
    }

    public Flux<TickerMetaItem> tickerMetaItemFlux(){
        DartDataConverter converter = new DartDataConverter();
        return converter.processTickers();
    }

    public void saveAllTickersToRedis(){
        tickerMetaItemFlux()
                .subscribe(tickerMetaItem -> {
                    final String companyName = tickerMetaItem.getCompanyName();
                    AutoCompleteTickerKeyBuilder builder = new AutoCompleteTickerKeyBuilder(companyName);
                    String key = builder.generateKey();

                    tickerMetaAutoCompleteRedisOps.opsForZSet()
                            .add(key, tickerMetaItem.getCompanyName() + "§§§", 1).block();

                    for(int i=1; i<companyName.length(); i++){
                        tickerMetaAutoCompleteRedisOps.opsForZSet()
                                .add(key, companyName.substring(0,i), 0).block();
                    }
                });
    }

    public Flux<String> searchCompanyNames(String companyName, Double min, Double max, int offset, int count){
        final String keyword = companyName.trim();
        int len = keyword.length();

        AutoCompleteTickerKeyBuilder builder = new AutoCompleteTickerKeyBuilder(companyName);
        final String key = builder.searchKey();

        // 참고 : https://www.programcreek.com/java-api-examples/?api=org.springframework.data.domain.Range
        final Bound<Double> lowerBound = Bound.inclusive(min);
        final Bound<Double> upperBound = Bound.inclusive(max);

        Range<Double> range = Range.of(lowerBound, upperBound);
        RedisZSetCommands.Limit limit = new RedisZSetCommands.Limit();

        limit.offset(offset);
        limit.count(count);

        List<String> results = new ArrayList<>();
        for(int i=len; i<count; i++){
            if(results.size() == count) break;
            Flux<ZSetOperations.TypedTuple<String>> typedTupleFlux = tickerMetaAutoCompleteRedisOps
                    .opsForZSet()
                    .reverseRangeByScoreWithScores(key+i, range, limit);

            typedTupleFlux.subscribe(tuple -> {
                if(results.size() < count){
                    String value = tuple.getValue().trim();
                    int minLen = Math.min(value.length(), keyword.length());
                    if(pattern.matcher(value).matches() && value.startsWith(keyword.substring(0, minLen))){
//                        System.out.println(value);
                        results.add(value.replace("§§§", ""));
                    }
                }
            });
        }

        return Flux.fromIterable(results);
    }

    /**
     * 회사명 : TickerMataItem
     * ticker : TickerMetaItem
     * 키/밸류로 하는 매핑 데이터를 캐시에 저장
     */
    public void saveAllCompanyNamesToRedis(){
        tickerMetaItemFlux()
                .subscribe(tickerMetaItem -> {
//                    SearchTickerKeyBuilder builderByCompanyName = new SearchTickerKeyBuilder(SearchTickerType.BY_COMPANY_NAME, tickerMetaItem);
                    SearchTickerKeyBuilder builderByCompanyName = SearchTickerKeyBuilder.newGenerateKeyBuilder(SearchTickerType.BY_COMPANY_NAME, tickerMetaItem);
                    tickerMetaMapOps.opsForValue().set(builderByCompanyName.generateKey(), tickerMetaItem).block();

//                    SearchTickerKeyBuilder builderByTicker = new SearchTickerKeyBuilder(SearchTickerType.BY_TICKER, tickerMetaItem);
                    SearchTickerKeyBuilder builderByTicker = SearchTickerKeyBuilder.newGenerateKeyBuilder(SearchTickerType.BY_TICKER, tickerMetaItem);
                    tickerMetaMapOps.opsForValue().set(builderByTicker.generateKey(), tickerMetaItem).block();
                });
    }

    /**
     * 회사명으로 검색해도 TickerMetaItem, ticker(회사코드)로 검색해도 TickerMetaItem 이 리턴된다.
     * @param query {companyName | ticker}
     * @return TickerMetaItem
     */
    public Mono<TickerMetaItem> searchTickerMetaItem(String query){
//        SearchTickerKeyBuilder searchTickerKeyBuilder = new SearchTickerKeyBuilder(SearchTickerType.BY_COMPANY_NAME);
        SearchTickerKeyBuilder searchTickerKeyBuilder = SearchTickerKeyBuilder.newSearchKeyBuilder(SearchTickerType.BY_COMPANY_NAME);
        String searchKey = searchTickerKeyBuilder.searchKey(query);
        return tickerMetaMapOps.opsForValue().get(searchKey);
    }

}