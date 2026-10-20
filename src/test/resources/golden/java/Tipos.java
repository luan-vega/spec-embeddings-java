package br.tipos;

import java.util.function.Function;

public interface Tipos {

    int contar(String texto);

    default String nome() {
        return "tipos";
    }

    enum Nivel {
        BAIXO(1), MEDIO(2) {
            @Override
            public int peso() {
                return 20;
            }
        },
        ALTO(3);

        private final int valor;

        Nivel(int valor) {
            this.valor = valor;
        }

        public int peso() {
            return valor * 10;
        }
    }

    record Ponto(int x, int y) {
        public Ponto {
            if (x < 0) {
                throw new IllegalArgumentException("x");
            }
        }

        public double distancia() {
            return Math.sqrt(x * x + y * y);
        }
    }

    @interface Marca {
        String valor() default "x";
    }

    class Utilitario {
        @Marca("a{b}")
        public <T extends Comparable<T>> T maior(T a, T b) {
            Function<T, Integer> f = v -> v.hashCode();
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    System.out.println("run");
                }
            };
            return f.apply(a) >= f.apply(b) ? a : b;
        }

        int[] tabela = {1, 2, 3};
    }
}
