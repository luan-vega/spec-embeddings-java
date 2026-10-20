package br.estoque;

public class Frete {

    /** Preço do transporte conforme o peso e a distância. */
    public double calcularFrete(double pesoKg, double distanciaKm) {
        return 5.0 + pesoKg * 0.8 + distanciaKm * 0.15;
    }

    public int estimarPrazoEntrega(double distanciaKm) {
        return (int) Math.ceil(distanciaKm / 400.0) + 1;
    }

    public String escolherTransportadora(double pesoKg) {
        return pesoKg > 30 ? "RODOVIARIO" : "EXPRESSO";
    }
}
