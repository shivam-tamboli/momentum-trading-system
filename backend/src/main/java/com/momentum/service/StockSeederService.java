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
                new Stock(null, "JPM", "JPMorgan Chase & Co.", "S&P 500"),
                new Stock(null, "V", "Visa Inc.", "S&P 500"),
                new Stock(null, "MA", "Mastercard Incorporated", "S&P 500"),
                new Stock(null, "UNH", "UnitedHealth Group Incorporated", "S&P 500"),
                new Stock(null, "JNJ", "Johnson & Johnson", "S&P 500"),
                new Stock(null, "PG", "The Procter & Gamble Company", "S&P 500"),
                new Stock(null, "HD", "The Home Depot, Inc.", "S&P 500"),
                new Stock(null, "BAC", "Bank of America Corporation", "S&P 500"),
                new Stock(null, "XOM", "Exxon Mobil Corporation", "S&P 500"),
                new Stock(null, "CVX", "Chevron Corporation", "S&P 500"),
                new Stock(null, "KO", "The Coca-Cola Company", "S&P 500"),
                new Stock(null, "PEP", "PepsiCo, Inc.", "S&P 500"),
                new Stock(null, "MRK", "Merck & Co., Inc.", "S&P 500"),
                new Stock(null, "ABBV", "AbbVie Inc.", "S&P 500"),
                new Stock(null, "LLY", "Eli Lilly and Company", "S&P 500"),

                new Stock(null, "DECK", "Deckers Outdoor Corporation", "S&P 400"),
                new Stock(null, "MEDP", "Medpace Holdings, Inc.", "S&P 400"),
                new Stock(null, "LNTH", "Lantheus Holdings, Inc.", "S&P 400"),
                new Stock(null, "HLNE", "Hamilton Lane Incorporated", "S&P 400"),
                new Stock(null, "CRVL", "CorVel Corporation", "S&P 400"),
                new Stock(null, "RBC", "RBC Bearings Incorporated", "S&P 400"),
                new Stock(null, "SAIA", "Saia, Inc.", "S&P 400"),
                new Stock(null, "UFPI", "UFP Industries, Inc.", "S&P 400"),
                new Stock(null, "PLXS", "Plexus Corp.", "S&P 400"),
                new Stock(null, "CSWI", "CSW Industrials, Inc.", "S&P 400"),
                new Stock(null, "TREX", "Trex Company, Inc.", "S&P 400"),
                new Stock(null, "LSTR", "Landstar System, Inc.", "S&P 400"),
                new Stock(null, "COOP", "Mr. Cooper Group Inc.", "S&P 400"),
                new Stock(null, "IBOC", "International Bancshares Corporation", "S&P 400"),
                new Stock(null, "SFNC", "Simmons First National Corporation", "S&P 400"),
                new Stock(null, "WSBC", "WesBanco, Inc.", "S&P 400"),
                new Stock(null, "TRMK", "Trustmark Corporation", "S&P 400"),
                new Stock(null, "HWC", "Hancock Whitney Corporation", "S&P 400"),
                new Stock(null, "MGEE", "MGE Energy, Inc.", "S&P 400"),
                new Stock(null, "FULT", "Fulton Financial Corporation", "S&P 400"),

                new Stock(null, "IESC", "IES Holdings, Inc.", "S&P 600"),
                new Stock(null, "SPNS", "Sapiens International Corporation", "S&P 600"),
                new Stock(null, "ACIW", "ACI Worldwide, Inc.", "S&P 600"),
                new Stock(null, "BV", "BrightView Holdings, Inc.", "S&P 600"),
                new Stock(null, "MYRG", "MYR Group Inc.", "S&P 600"),
                new Stock(null, "CVCO", "Cavco Industries, Inc.", "S&P 600"),
                new Stock(null, "KFRC", "Kforce Inc.", "S&P 600"),
                new Stock(null, "AMSF", "AMERISAFE, Inc.", "S&P 600"),
                new Stock(null, "WDFC", "WD-40 Company", "S&P 600"),
                new Stock(null, "DCOM", "Dime Community Bancshares, Inc.", "S&P 600"),
                new Stock(null, "NBTB", "NBT Bancorp Inc.", "S&P 600"),
                new Stock(null, "SBCF", "Seacoast Banking Corporation of Florida", "S&P 600"),
                new Stock(null, "HAFC", "Hanmi Financial Corporation", "S&P 600"),
                new Stock(null, "FFIN", "First Financial Bankshares, Inc.", "S&P 600"),
                new Stock(null, "FBIZ", "First Business Financial Corporation", "S&P 600"),
                new Stock(null, "CBTX", "CBTX, Inc.", "S&P 600"),
                new Stock(null, "WSFS", "WSFS Financial Corporation", "S&P 600"),
                new Stock(null, "BFIN", "BankFinancial Corporation", "S&P 600"),
                new Stock(null, "MGEE", "MGE Energy, Inc.", "S&P 600"),
                new Stock(null, "FULT", "Fulton Financial Corporation", "S&P 600"),

                new Stock(null, "META", "Meta Platforms, Inc.", "Nasdaq 100"),
                new Stock(null, "TSLA", "Tesla, Inc.", "Nasdaq 100"),
                new Stock(null, "NFLX", "Netflix, Inc.", "Nasdaq 100"),
                new Stock(null, "ADBE", "Adobe Inc.", "Nasdaq 100"),
                new Stock(null, "PYPL", "PayPal Holdings, Inc.", "Nasdaq 100"),
                new Stock(null, "MSFT", "Microsoft Corporation", "Nasdaq 100"),
                new Stock(null, "AAPL", "Apple Inc.", "Nasdaq 100"),
                new Stock(null, "AMZN", "Amazon.com, Inc.", "Nasdaq 100"),
                new Stock(null, "GOOGL", "Alphabet Inc.", "Nasdaq 100"),
                new Stock(null, "NVDA", "NVIDIA Corporation", "Nasdaq 100"),
                new Stock(null, "AVGO", "Broadcom Inc.", "Nasdaq 100"),
                new Stock(null, "ASML", "ASML Holding N.V.", "Nasdaq 100"),
                new Stock(null, "COST", "Costco Wholesale Corporation", "Nasdaq 100"),
                new Stock(null, "PEP", "PepsiCo, Inc.", "Nasdaq 100"),
                new Stock(null, "CSCO", "Cisco Systems, Inc.", "Nasdaq 100"),
                new Stock(null, "INTC", "Intel Corporation", "Nasdaq 100"),
                new Stock(null, "INTU", "Intuit Inc.", "Nasdaq 100"),
                new Stock(null, "QCOM", "QUALCOMM Incorporated", "Nasdaq 100"),
                new Stock(null, "AMGN", "Amgen Inc.", "Nasdaq 100"),
                new Stock(null, "ISRG", "Intuitive Surgical, Inc.", "Nasdaq 100")
        );

        Set<String> existingKeys = stockRepository.findAll().stream()
                .map(s -> s.getSymbol() + "|" + s.getIndexName())
                .collect(Collectors.toSet());

        for (Stock stock : stocksToSeed) {
            if (!existingKeys.contains(stock.getSymbol() + "|" + stock.getIndexName())) {
                stockRepository.save(stock);
            }
        }
    }
}
