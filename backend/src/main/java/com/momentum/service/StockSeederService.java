package com.momentum.service;

import com.momentum.model.Stock;
import com.momentum.repository.StockRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class StockSeederService {

    private final StockRepository stockRepository;

    public StockSeederService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedStocks() {
        List<Stock> stocksToSeed = List.of(
                new Stock(null, "AAPL", "Apple Inc.", "S&P 500"),
                new Stock(null, "MSFT", "Microsoft Corporation", "S&P 500"),
                new Stock(null, "GOOGL", "Alphabet Inc.", "S&P 500"),
                new Stock(null, "AMZN", "Amazon.com, Inc.", "S&P 500"),
                new Stock(null, "NVDA", "NVIDIA Corporation", "S&P 500"),

                new Stock(null, "DECK", "Deckers Outdoor Corporation", "S&P 400"),
                new Stock(null, "MEDP", "Medpace Holdings, Inc.", "S&P 400"),
                new Stock(null, "LNTH", "Lantheus Holdings, Inc.", "S&P 400"),
                new Stock(null, "HLNE", "Hamilton Lane Incorporated", "S&P 400"),
                new Stock(null, "CRVL", "CorVel Corporation", "S&P 400"),

                new Stock(null, "IESC", "IES Holdings, Inc.", "S&P 600"),
                new Stock(null, "SPNS", "Sapiens International Corporation", "S&P 600"),
                new Stock(null, "ACIW", "ACI Worldwide, Inc.", "S&P 600"),
                new Stock(null, "BV", "BrightView Holdings, Inc.", "S&P 600"),
                new Stock(null, "MYRG", "MYR Group Inc.", "S&P 600"),

                new Stock(null, "META", "Meta Platforms, Inc.", "Nasdaq 100"),
                new Stock(null, "TSLA", "Tesla, Inc.", "Nasdaq 100"),
                new Stock(null, "NFLX", "Netflix, Inc.", "Nasdaq 100"),
                new Stock(null, "ADBE", "Adobe Inc.", "Nasdaq 100"),
                new Stock(null, "PYPL", "PayPal Holdings, Inc.", "Nasdaq 100")
        );

        Set<String> existingSymbols = stockRepository.findAll().stream()
                .map(Stock::getSymbol)
                .collect(Collectors.toSet());

        for (Stock stock : stocksToSeed) {
            if (!existingSymbols.contains(stock.getSymbol())) {
                stockRepository.save(stock);
            }
        }
    }
}
