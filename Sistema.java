// PUCRS - Escola Politécnica - Sistemas Operacionais
// Prof. Fernando Dotti
// Código fornecido como parte da solução do projeto de Sistemas Operacionais

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.LinkedBlockingQueue;

public class Sistema {

    // ------------------- ESTADOS DO PROCESSO -------------------
    public enum ProcessState {
        NEW, READY, RUNNING, BLOCKED, TERMINATED
    }

    // ------------------- PROCESS CONTROL BLOCK (PCB) -------------------
    public class PCB {
        public int id;
        public int pc;
        public int[] reg;
        public int[] tabelaPaginas;
        public ProcessState estado;
        public String programName;

        private static int nextId = 0;

        public PCB(int[] _tabelaPaginas, String _programName) {
            this.id = nextId++;
            this.tabelaPaginas = _tabelaPaginas;
            this.programName = _programName;
            this.pc = 0; // Ponto de entrada LÓGICO é sempre 0
            this.reg = new int[10]; // Registradores zerados
            Arrays.fill(this.reg, 0);
            this.estado = ProcessState.NEW;
        }
    }

    // ------------------- CLASSE IORequest -------------------
    public class IORequest {
        public PCB pcb;
        public int endLogico;
        public int tipo;

        public IORequest(PCB _pcb, int _endLogico, int _tipo) {
            this.pcb = _pcb;
            this.endLogico = _endLogico;
            this.tipo = _tipo;
        }
    }

    // ------------------- GERENTE DE PROCESSOS -------------------
    public class GerenteProcessos {
        public LinkedList<PCB> prontos;
        public LinkedList<PCB> bloqueados; // Nova fila de bloqueados
        public PCB rodando;
        private GerenteMemoria gm;
        private HW hw;
        private int tamPg;
        private Utilities utils;
        private SO so;

        public GerenteProcessos(HW _hw, GerenteMemoria _gm, int _tamPg, Utilities _utils, SO _so) {
            this.hw = _hw;
            this.gm = _gm;
            this.tamPg = _tamPg;
            this.utils = _utils;
            this.so = _so;
            this.prontos = new LinkedList<>();
            this.bloqueados = new LinkedList<>();
            this.rodando = null;
        }

        public boolean criaProcesso(Program p) {
            if (p == null || p.image == null) {
                System.out.println("GP: Erro: Programa nulo.");
                return false;
            }
            int nroPalavras = p.image.length;
            int[] tabelaPaginas = gm.aloca(nroPalavras);
            if (tabelaPaginas == null) {
                System.out.println("GP: Erro: Memória insuficiente para o programa " + p.name);
                return false;
            }
            PCB pcb = new PCB(tabelaPaginas, p.name);
            carregarPrograma(p.image, pcb);
            pcb.estado = ProcessState.READY;
            prontos.add(pcb);
            System.out.println("GP: Processo " + pcb.id + " (" + pcb.programName + ") criado.");
            
            // Se for o primeiro processo, libera o escalonador
            if (rodando == null && prontos.size() == 1) {
                so.semaEscalonador.release();
            }
            return true;
        }

        private void carregarPrograma(Word[] programa, PCB pcb) {
            for (int endLogico = 0; endLogico < programa.length; endLogico++) {
                int pagina = endLogico / tamPg;
                int offset = endLogico % tamPg;
                int frame = pcb.tabelaPaginas[pagina];
                int endFisico = (frame * tamPg) + offset;
                hw.mem.pos[endFisico] = new Word(programa[endLogico].opc, programa[endLogico].ra, programa[endLogico].rb, programa[endLogico].p);
            }
            System.out.println("GP: Carga do programa " + pcb.programName + " concluída.");
        }

        public void desalocaProcesso(int id) {
            // Procura o PCB na fila de prontos
            PCB pcb = null;

            if (rodando != null && rodando.id == id) {
                pcb = rodando;
                rodando = null;
                System.out.println("GP: Desalocando processo rodando " + id);
            } 
            else {
                Iterator<PCB> iterator = prontos.iterator();
                while (iterator.hasNext()) {
                    PCB p = iterator.next();
                    if (p.id == id) {
                        pcb = p;
                        iterator.remove();
                        System.out.println("GP: Desalocando processo da fila " + id);
                        break;
                    }
                }
                
                // Se não encontrou nos prontos, procura nos bloqueados
                if (pcb == null) {
                    iterator = bloqueados.iterator();
                    while (iterator.hasNext()) {
                        PCB p = iterator.next();
                        if (p.id == id) {
                            pcb = p;
                            iterator.remove();
                            System.out.println("GP: Desalocando processo bloqueado " + id);
                            break;
                        }
                    }
                }
            }
            
            if (pcb == null) {
                System.out.println("GP: Processo " + id + " não encontrado.");
                return;
            }
            gm.desaloca(pcb.tabelaPaginas);
            pcb.estado = ProcessState.TERMINATED;
            System.out.println("GP: Processo " + id + " desalocado.");
        }

        public PCB findAndRemoveFromBlocked(int pid) {
            Iterator<PCB> iterator = bloqueados.iterator();
            while (iterator.hasNext()) {
                PCB p = iterator.next();
                if (p.id == pid) {
                    iterator.remove();
                    return p;
                }
            }
            return null;
        }

        public void ps() {
            System.out.println("=== LISTA DE PROCESSOS ===");
            System.out.println("ID\tEstado\t\tPrograma");
            if (rodando != null) {
                System.out.println(rodando.id + "\tRUNNING\t\t" + rodando.programName);
            }
            for (PCB p : prontos) {
                System.out.println(p.id + "\tREADY\t\t" + p.programName);
            }
            for (PCB p : bloqueados) {
                System.out.println(p.id + "\tBLOCKED\t\t" + p.programName);
            }
            if (rodando == null && prontos.isEmpty() && bloqueados.isEmpty()) {
                System.out.println("Nenhum processo ativo.");
            }
        }

        public void dump(int id) {
            PCB pcb = null;
            if (rodando != null && rodando.id == id) {
                pcb = rodando;
            } else {
                for (PCB p : prontos) {
                    if (p.id == id) {
                        pcb = p;
                        break;
                    }
                }
                if (pcb == null) {
                    for (PCB p : bloqueados) {
                        if (p.id == id) {
                            pcb = p;
                            break;
                        }
                    }
                }
            }
            if (pcb == null) {
                System.out.println("GP: Processo " + id + " não encontrado.");
                return;
            }
            System.out.println("=== DUMP DO PROCESSO " + id + " (" + pcb.programName + ") ===");
            System.out.println("PC: " + pcb.pc);
            System.out.println("Estado: " + pcb.estado);
            System.out.println("Registradores:");
            for (int i = 0; i < pcb.reg.length; i++) {
                System.out.println("  r[" + i + "]: " + pcb.reg[i]);
            }
            System.out.println("Tabela de Páginas:");
            for (int i = 0; i < pcb.tabelaPaginas.length; i++) {
                System.out.println("  Página " + i + " -> Frame " + pcb.tabelaPaginas[i]);
            }
        }

        public void escalonar() {
            prontos.removeIf(p -> p.estado == ProcessState.TERMINATED);

            if (prontos.isEmpty()) {
                rodando = null;
                System.out.println("GP: Todos os processos terminaram. Retornando ao CLI.");
                return;
            }
            
            PCB proximo = prontos.removeFirst();

            if (proximo.estado == ProcessState.TERMINATED) {
                System.out.println("GP: Processo " + proximo.id + " já terminado. Pulando...");
                escalonar();
                return;
            }

            proximo.estado = ProcessState.RUNNING;
            rodando = proximo;
            
            // Restaurar contexto do processo na CPU
            hw.cpu.setContext(rodando.pc, rodando.reg, rodando.tabelaPaginas);
            
            System.out.println("GP: Escalonando processo " + rodando.id + " (" + rodando.programName + ")");

            if (prontos.isEmpty() && rodando == null) {
                System.out.println("\n=== TODOS OS PROCESSOS TERMINARAM ===");
                System.out.println("Retornando ao prompt de comandos...");
                System.out.print("> "); // Para manter a formatação do CLI
            }
        }

        public void exec(int id) {
            PCB pcb = null;
            for (PCB p : prontos) {
                if (p.id == id) {
                    pcb = p;
                    break;
                }
            }
            if (pcb == null) {
                System.out.println("GP: Processo " + id + " não encontrado na fila de prontos.");
                return;
            }

            System.out.println("GP: Adicionando processo " + id + " (" + pcb.programName + ") para execução");
        }
    }

    // -------------------------------------------------------------------------------------------------------
    // --------------------- H A R D W A R E - definicoes de HW
    // ----------------------------------------------

    public class Memory {
        public Word[] pos; // pos[i] é a posição i da memória. cada posição é uma palavra.

        public Memory(int size) {
            pos = new Word[size];
            for (int i = 0; i < pos.length; i++) {
                pos[i] = new Word(Opcode.___, -1, -1, -1);
            }
            ; // cada posicao da memoria inicializada
        }
    }

    public class Word {    // cada posicao da memoria tem uma instrucao (ou um dado)
        public Opcode opc; //
        public int ra;     // indice do primeiro registrador da operacao (Rs ou Rd cfe opcode na tabela)
        public int rb;     // indice do segundo registrador da operacao (Rc ou Rs cfe operacao)
        public int p;      // parametro para instrucao (k ou A cfe operacao), ou o dado, se opcode = DADO

        public Word(Opcode _opc, int _ra, int _rb, int _p) { // vide definição da VM - colunas vermelhas da tabela
            opc = _opc;
            ra = _ra;
            rb = _rb;
            p  = _p;
        }
    }

    // -------------------------------------------------------------------------------------------------------
    // --------------------- C P U - definicoes da CPU
    // -----------------------------------------------------

    public enum Opcode {
        DATA, ___,              // se memoria nesta posicao tem um dado, usa DATA, se nao usada ee NULO ___
        JMP, JMPI, JMPIG, JMPIL, JMPIE, // desvios
        JMPIM, JMPIGM, JMPILM, JMPIEM,
        JMPIGK, JMPILK, JMPIEK, JMPIGT,
        ADDI, SUBI, ADD, SUB, MULT,    // matematicos
        LDI, LDD, STD, LDX, STX, MOVE, // movimentacao
        SYSCALL, STOP                  // chamada de sistema e parada
    }

    public enum Interrupts {           // possiveis interrupcoes que esta CPU gera
        noInterrupt, intEnderecoInvalido, intInstrucaoInvalida, intOverflow, intFimDeFatiaDeTempo, intIO;
    }

    // ------------------- GERENTE DE MEMÓRIA COM PAGINAÇÃO -------------------
    public class GerenteMemoria {
        private boolean[] framesOcupados;
        private int numFramesLivres;
        private int tamPg;

        public GerenteMemoria(int tamMem, int tamPg) {
            int numFrames = tamMem / tamPg;
            this.tamPg = tamPg;
            this.framesOcupados = new boolean[numFrames];
            Arrays.fill(this.framesOcupados, false);
            this.numFramesLivres = numFrames;
        }

        public int[] aloca(int nroPalavras) {
            int numPaginas = (int) Math.ceil((double) nroPalavras / tamPg);
            if (numPaginas > this.numFramesLivres) {
                return null;
            }
            int[] tabelaPaginas = new int[numPaginas];
            for (int i = 0; i < numPaginas; i++) {
                int frameEncontrado = -1;
                for (int j = 0; j < framesOcupados.length; j++) {
                    if (!framesOcupados[j]) {
                        frameEncontrado = j;
                        break;
                    }
                }
                framesOcupados[frameEncontrado] = true;
                tabelaPaginas[i] = frameEncontrado;
                this.numFramesLivres--;
            }
            return tabelaPaginas;
        }

        public void desaloca(int[] tabelaPaginas) {
            if (tabelaPaginas == null) return;
            for (int frameIndex : tabelaPaginas) {
                if (frameIndex >= 0 && frameIndex < framesOcupados.length && framesOcupados[frameIndex]) {
                    framesOcupados[frameIndex] = false;
                    this.numFramesLivres++;
                }
            }
        }
    }

    public class CPU {
        private int maxInt; // valores maximo e minimo para inteiros nesta cpu
        private int minInt;
                        // CONTEXTO da CPU ...
        private int pc;     // ... composto de program counter,
        private Word ir;    // instruction register,
        private int[] reg;  // registradores da CPU
        private Interrupts irpt; // durante instrucao, interrupcao pode ser sinalizada
                        // FIM CONTEXTO DA CPU: tudo que precisa sobre o estado de um processo para
                        // executa-lo
                        // nas proximas versoes isto pode modificar

        private Word[] m;   // m é o array de memória "física", CPU tem uma ref a m para acessar

        private InterruptHandling ih;    // significa desvio para rotinas de tratamento de Int - se int ligada, desvia
        private SysCallHandling sysCall; // significa desvio para tratamento de chamadas de sistema

        private boolean cpuStop;    // flag para parar CPU - caso de interrupcao que acaba o processo, ou chamada stop - 
                                    // nesta versao acaba o sistema no fim do prog

                        // auxilio aa depuração
        private boolean debug;      // se true entao mostra cada instrucao em execucao
        private Utilities u;        // para debug (dump)

        // MMU - Gerenciamento de Memória com Paginação
        private int[] regTabelaPaginas;  // Tabela de páginas do processo atual
        private int tamPg;               // Tamanho da página

        // Escalonamento - Quantum
        private int instrucoesExecutadas = 0;
        private final int DELTA_INSTRUCOES = 4; // Quantum de 4 instruções

        // Mecanismo para interrupção de E/S
        private volatile boolean ioInterruptPending = false;
        private volatile int ioProcessId = -1;

        public CPU(Memory _mem, boolean _debug, int _tamPg) { // ref a MEMORIA passada na criacao da CPU
            maxInt = 32767;            // capacidade de representacao modelada
            minInt = -32767;           // se exceder deve gerar interrupcao de overflow
            m = _mem.pos;              // usa o atributo 'm' para acessar a memoria, só para ficar mais pratico
            reg = new int[10];         // aloca o espaço dos registradores - regs 8 e 9 usados somente para IO

            debug = _debug;            // se true, print da instrucao em execucao
            tamPg = _tamPg;            // tamanho da página para MMU
            regTabelaPaginas = null;   // inicialmente sem tabela de páginas

        }

        public void setDebug(boolean _debug) {
            debug = _debug;
        }

        public void setTabelaPaginas(int[] tabela) {
            this.regTabelaPaginas = tabela;
        }

        public void setContext(int _pc, int[] _reg, int[] _tabelaPaginas) {
            this.pc = _pc;
            this.reg = _reg;
            this.regTabelaPaginas = _tabelaPaginas;
            this.irpt = Interrupts.noInterrupt;
            this.instrucoesExecutadas = 0; // Reset do contador de quantum
        }

        public int getPc() {
            return pc;
        }

        public int[] getReg() {
            return reg;
        }

        public synchronized void setIOInterrupt(int pid) {
            ioInterruptPending = true;
            ioProcessId = pid;
        }

        public int getPendingIOProcessId() {
            return ioProcessId;
        }

        private int translate(int logicalAddress) {
            // Se não há paginação ativa, retorna endereço direto
            if (regTabelaPaginas == null) {
                return logicalAddress;
            }
            
            int pageNumber = logicalAddress / tamPg;
            int offset = logicalAddress % tamPg;
            
            // Verifica se a página é válida
            if (pageNumber < 0 || pageNumber >= regTabelaPaginas.length) {
                irpt = Interrupts.intEnderecoInvalido;
                return -1;
            }
            
            int frameNumber = regTabelaPaginas[pageNumber];
            
            // Physical Address Calculation
            int physicalAddress = (frameNumber * tamPg) + offset;
            return physicalAddress;
        }

        public void setAddressOfHandlers(InterruptHandling _ih, SysCallHandling _sysCall) {
            ih = _ih;                   // aponta para rotinas de tratamento de int
            sysCall = _sysCall;        // aponta para rotinas de tratamento de chamadas de sistema
        }

        public void setUtilities(Utilities _u) {
            u = _u;                     // aponta para rotinas utilitárias - fazer dump da memória na tela
        }


                          // verificação de enderecamento 
        private boolean legal(int e) { // todo acesso a memoria tem que ser verificado se é válido - 
                          // aqui no caso se o endereco é um endereco valido em toda memoria
            if (e >= 0 && e < m.length) {
                return true;
            } else {
                irpt = Interrupts.intEnderecoInvalido;    // se nao for liga interrupcao no meio da exec da instrucao
                return false;
            }
        }

        private boolean testOverflow(int v) {             // toda operacao matematica deve avaliar se ocorre overflow
            if ((v < minInt) || (v > maxInt)) {
                irpt = Interrupts.intOverflow;            // se houver liga interrupcao no meio da exec da instrucao
                return false;
            }
            ;
            return true;
        }

        public void run_one_instruction() {                               // execucao da CPU de UMA instrução
            cpuStop = false;
            
            // Verifica interrupção de E/S pendente
            if (ioInterruptPending) {
                irpt = Interrupts.intIO;
                ioInterruptPending = false;
            }
            
            // --------------------------------------------------------------------------------------------------
            // FASE DE FETCH
            int physPC = translate(pc);
            if (legal(physPC)) { // pc valido
                ir = m[physPC];  // <<<<<<<<<<<< AQUI faz FETCH - busca posicao da memoria apontada por pc, guarda em ir
                                 // resto é dump de debug
                if (debug) {
                    System.out.print("                                                         regs: ");
                    for (int i = 0; i < 10; i++) {
                        System.out.print(" r[" + i + "]:" + reg[i]);
                    }
                    ;
                    System.out.println();
                }
                if (debug) {
                    System.out.print("                         pc: " + pc + "       exec: ");
                    u.dump(ir);
                }

            // --------------------------------------------------------------------------------------------------
            // FASE DE EXECUCAO DA INSTRUCAO CARREGADA NO ir
                switch (ir.opc) {       // conforme the opcode (código de operação) executa

                    // Instrucoes de Busca e Armazenamento em Memoria
                    case LDI: // Rd ← k        veja a tabela de instrucoes do HW simulado para entender a semantica da instrucao
                        reg[ir.ra] = ir.p;
                        pc++;
                        break;
                    case LDD: // Rd <- [A]
                        int physAddrLDD = translate(ir.p);
                        if (legal(physAddrLDD)) {
                            reg[ir.ra] = m[physAddrLDD].p;
                            pc++;
                        }
                        break;
                    case LDX: // RD <- [RS] // NOVA
                        int logicalAddrLDX = reg[ir.rb];
                        int physAddrLDX = translate(logicalAddrLDX);
                        if (legal(physAddrLDX)) {
                            reg[ir.ra] = m[physAddrLDX].p;
                            pc++;
                        }
                        break;
                    case STD: // [A] ← Rs
                        int physAddrSTD = translate(ir.p);
                        if (legal(physAddrSTD)) {
                            m[physAddrSTD].opc = Opcode.DATA;
                            m[physAddrSTD].p = reg[ir.ra];
                            pc++;
                            if (debug) 
                                {   System.out.print("                                                 ");   
                                    u.dump(physAddrSTD,physAddrSTD+1);                             
                                }
                            }
                        break;
                    case STX: // [Rd] ←Rs
                        int logicalAddrSTX = reg[ir.ra];
                        int physAddrSTX = translate(logicalAddrSTX);
                        if (legal(physAddrSTX)) {
                            m[physAddrSTX].opc = Opcode.DATA;
                            m[physAddrSTX].p = reg[ir.rb];
                            pc++;
                        }
                        ;
                        break;
                    case MOVE: // RD <- RS
                        reg[ir.ra] = reg[ir.rb];
                        pc++;
                        break;
                    // Instrucoes Aritmeticas
                    case ADD: // Rd ← Rd + Rs
                        reg[ir.ra] = reg[ir.ra] + reg[ir.rb];
                        testOverflow(reg[ir.ra]);
                        pc++;
                        break;
                    case ADDI: // Rd ← Rd + k
                        reg[ir.ra] = reg[ir.ra] + ir.p;
                        testOverflow(reg[ir.ra]);
                        pc++;
                        break;
                    case SUB: // Rd ← Rd - Rs
                        reg[ir.ra] = reg[ir.ra] - reg[ir.rb];
                        testOverflow(reg[ir.ra]);
                        pc++;
                        break;
                    case SUBI: // RD <- RD - k // NOVA
                        reg[ir.ra] = reg[ir.ra] - ir.p;
                        testOverflow(reg[ir.ra]);
                        pc++;
                        break;
                    case MULT: // Rd <- Rd * Rs
                        reg[ir.ra] = reg[ir.ra] * reg[ir.rb];
                        testOverflow(reg[ir.ra]);
                        pc++;
                        break;

                    // Instrucoes JUMP
                    case JMP: // PC <- k
                        pc = ir.p;
                        break;
                    case JMPIM: // PC <- [A]
                        int physAddrJMPIM = translate(ir.p);
                        if (legal(physAddrJMPIM)) {
                            pc = m[physAddrJMPIM].p;
                        }
                        break;
                    case JMPIG: // If Rc > 0 Then PC ← Rs Else PC ← PC +1
                        if (reg[ir.rb] > 0) {
                            pc = reg[ir.ra];
                        } else {
                            pc++;
                        }
                        break;
                    case JMPIGK: // If RC > 0 then PC <- k else PC++
                        if (reg[ir.rb] > 0) {
                            pc = ir.p;
                        } else {
                            pc++;
                        }
                        break;
                    case JMPILK: // If RC < 0 then PC <- k else PC++
                        if (reg[ir.rb] < 0) {
                            pc = ir.p;
                        } else {
                            pc++;
                        }
                        break;
                    case JMPIEK: // If RC = 0 then PC <- k else PC++
                        if (reg[ir.rb] == 0) {
                            pc = ir.p;
                        } else {
                            pc++;
                        }
                        break;
                    case JMPIL: // if Rc < 0 then PC <- Rs Else PC <- PC +1
                        if (reg[ir.rb] < 0) {
                            pc = reg[ir.ra];
                        } else {
                            pc++;
                        }
                        break;
                    case JMPIE: // If Rc = 0 Then PC <- Rs Else PC <- PC +1
                        if (reg[ir.rb] == 0) {
                            pc = reg[ir.ra];
                        } else {
                            pc++;
                        }
                        break;
                    case JMPIGM: // If RC > 0 then PC <- [A] else PC++
                        int physAddrJMPIGM = translate(ir.p);
                        if (legal(physAddrJMPIGM)){
                            if (reg[ir.rb] > 0) {
                               pc = m[physAddrJMPIGM].p;
                            } else {
                                pc++;
                           }
                        }
                        break;
                    case JMPILM: // If RC < 0 then PC <- k else PC++
                        int physAddrJMPILM = translate(ir.p);
                        if (legal(physAddrJMPILM)) {
                            if (reg[ir.rb] < 0) {
                                pc = m[physAddrJMPILM].p;
                            } else {
                                pc++;
                            }
                        }
                        break;
                    case JMPIEM: // If RC = 0 then PC <- k else PC++
                        int physAddrJMPIEM = translate(ir.p);
                        if (legal(physAddrJMPIEM)) {
                            if (reg[ir.rb] == 0) {
                                pc = m[physAddrJMPIEM].p;
                            } else {
                                pc++;
                            }
                        }
                        break;
                    case JMPIGT: // If RS>RC then PC <- k else PC++
                        if (reg[ir.ra] > reg[ir.rb]) {
                            pc = ir.p;
                        } else {
                            pc++;
                        }
                        break;

                    case DATA: // pc está sobre área supostamente de dados
                        irpt = Interrupts.intInstrucaoInvalida;
                        break;

                    // Chamadas de sistema
                    case SYSCALL:
                        sysCall.handle(); // <<<<< aqui desvia para rotina de chamada de sistema, no momento so
                                              // temos IO
                        pc++;
                        break;

                    case STOP: // por enquanto, para execucao
                        sysCall.stop();
                        cpuStop = true;
                        break;

                    // Inexistente
                    default:
                        irpt = Interrupts.intInstrucaoInvalida;
                        break;
                }
            }
            
            // Controle do Quantum - Preempção
            instrucoesExecutadas++;
            if (instrucoesExecutadas >= DELTA_INSTRUCOES) {
                irpt = Interrupts.intFimDeFatiaDeTempo;
            }
            
            // --------------------------------------------------------------------------------------------------
            // VERIFICA INTERRUPÇÃO !!! - TERCEIRA FASE DO CICLO DE INSTRUÇÕES
            if (irpt != Interrupts.noInterrupt) { // existe interrupção
                ih.handle(irpt);                  // desvia para rotina de tratamento - esta rotina é do SO
                cpuStop = true;                   // nesta versao, para a CPU
            }
        }
    }
    // ------------------ C P U - fim
    // -----------------------------------------------------------------------
    // ------------------------------------------------------------------------------------------------------

    // ------------------- HW - constituido de CPU e MEMORIA
    // -----------------------------------------------
    public class HW {
        public Memory mem;
        public CPU cpu;
        public int tamPg;  // Tamanho da página

        public HW(int tamMem, int _tamPg) {
            mem = new Memory(tamMem);
            tamPg = _tamPg;
            cpu = new CPU(mem, true, _tamPg); // true liga debug
        }
    }
    // -------------------------------------------------------------------------------------------------------

    // --------------------H A R D W A R E - fim
    // -------------------------------------------------------------
    // -------------------------------------------------------------------------------------------------------

    // ///////////////////////////////////////////////////////////////////////////////////////////////////////

    // -------------------------------------------------------------------------------------------------------
    // -------------------------------------------------------------------------------------------------------
    // ------------------- SW - inicio - Sistema Operacional
    // -------------------------------------------------

    // ------------------- THREADS DO SISTEMA -------------------

    public class ThreadEscalonador implements Runnable {
        @Override
        public void run() {
            while(true) {
                try {
                    so.semaEscalonador.acquire(); // (1) Aguarda liberação (do Timer, STOP, Chamada IO, ou GP)
                    PCB proximo = so.gp.prontos.pollFirst(); // Pega o primeiro da fila de prontos
                    if (proximo != null) {
                        proximo.estado = ProcessState.RUNNING;
                        so.gp.rodando = proximo;
                        hw.cpu.setContext(proximo.pc, proximo.reg, proximo.tabelaPaginas); // (2) Restaura estado na CPU
                        so.semaCPU.release(); // (3) Libera a ThreadCPU para executar
                    } else {
                        // Fila de prontos vazia. O sistema fica ocioso.
                        so.gp.rodando = null;
                    }
                } catch (InterruptedException e) { e.printStackTrace(); }
            }
        }
    }

    public class ThreadCPU implements Runnable {
        @Override
        public void run() {
            while(true) {
                try {
                    so.semaCPU.acquire(); // (1) Aguarda liberação do Escalonador
                    while (so.gp.rodando != null) { // Loop da fatia de tempo
                        hw.cpu.run_one_instruction(); // (2) Executa uma instrução
                        // 'run_one_instruction' detecta interrupções (Timer, IO, Falha) e chama 'ih.handle()'
                        // 'ih.handle()' (ou 'sc.handle()') irá setar 'so.gp.rodando = null' e liberar o Escalonador,
                        // quebrando este loop 'while' e fazendo a ThreadCPU voltar a aguardar em (1).
                    }
                } catch (InterruptedException e) { e.printStackTrace(); }
            }
        }
    }

    public class ThreadConsole implements Runnable {
        @Override
        public void run() {
            while(true) {
                try {
                    IORequest req = so.filaPedidosConsole.take(); // (1) Aguarda pedido na fila (bloqueante)
                    PCB pcb = req.pcb;
                    int endFisico = hw.cpu.translate(req.endLogico);
                    // (2) Simula E/S (DMA)
                    if (req.tipo == 1) { // IN (Leitura)
                        System.out.print("    > CONSOLE INPUT (para pid: " + pcb.id + ", end: " + req.endLogico + "): ");
                        Scanner s = new Scanner(System.in); // SIMULAÇÃO: Scanner do console
                        int valor = s.nextInt();
                        hw.mem.pos[endFisico].p = valor;
                        hw.mem.pos[endFisico].opc = Opcode.DATA;
                    } else if (req.tipo == 2) { // OUT (Escrita)
                        System.out.println("    > CONSOLE OUTPUT (de pid: " + pcb.id + ", end: " + req.endLogico + "): " + hw.mem.pos[endFisico].p);
                    }
                    // (3) Simula fim da E/S e gera interrupção
                    hw.cpu.setIOInterrupt(pcb.id);
                } catch (InterruptedException e) { e.printStackTrace(); }
            }
        }
    }

    // ------------------- I N T E R R U P C O E S - rotinas de tratamento
    // ----------------------------------
    public class InterruptHandling {
        private SO so;

        public InterruptHandling(SO _so) {
            so = _so;
        }

        public void handle(Interrupts irpt) {
            switch (irpt) {
                case intFimDeFatiaDeTempo:
                    PCB processoAtual = so.gp.rodando;
                    processoAtual.pc = hw.cpu.getPc(); // Salva PC
                    processoAtual.estado = ProcessState.READY;
                    so.gp.prontos.addLast(processoAtual); // Põe no FIM da fila de prontos
                    so.gp.rodando = null;
                    so.semaEscalonador.release(); // Libera o Escalonador
                    break;
                    
                case intIO:
                    int pid = hw.cpu.getPendingIOProcessId();
                    PCB pcb = so.gp.findAndRemoveFromBlocked(pid);
                    if (pcb != null) {
                        pcb.estado = ProcessState.READY;
                        so.gp.prontos.addLast(pcb); // Põe no FIM da fila de prontos
                        System.out.println("    > INT IO: Processo " + pcb.id + " desbloqueado -> READY.");
                    }
                    // IMPORTANTE: NÃO libera o escalonador. O processo 'rodando' atual continua.
                    break;
                    
                case intEnderecoInvalido:
                case intInstrucaoInvalida:
                case intOverflow:
                    System.out.println("!!!! INTERRUPÇÃO FATAL: " + irpt + " no Processo " + so.gp.rodando.id);
                    PCB processoComErro = so.gp.rodando;
                    so.gp.rodando = null;
                    so.gp.desalocaProcesso(processoComErro.id);
                    so.semaEscalonador.release();
                    break;
                    
                default:
                    System.out.println("IH: Interrupção não tratada: " + irpt);
                    break;
            }
        }
    }

    // ------------------- C H A M A D A S D E S I S T E M A - rotinas de tratamento
    // ----------------------
    public class SysCallHandling {
        private HW hw;
        private SO so;

        public SysCallHandling(HW _hw, SO _so) {
            hw = _hw;
            so = _so;
        }

        public void stop() { // chamada de sistema indicando final de programa
            System.out.println("    > SYSCALL STOP: Processo " + so.gp.rodando.id + " terminado.");
            PCB processoTerminado = so.gp.rodando;
            so.gp.rodando = null;
            so.gp.desalocaProcesso(processoTerminado.id);
            so.semaEscalonador.release(); // Libera o Escalonador para trocar o processo
        }

        public void handle() { // chamada de sistema 
            PCB processoAtual = so.gp.rodando;
            processoAtual.pc = hw.cpu.getPc(); // Salva o PC atual (instrução *após* a syscall)
            int tipoChamada = hw.cpu.reg[8];
            if (tipoChamada == 1 || tipoChamada == 2) { // CHAMADA DE E/S (IN ou OUT)
                System.out.println("    > TRAP: Processo " + processoAtual.id + " solicitou E/S.");
                processoAtual.estado = ProcessState.BLOCKED;
                so.gp.bloqueados.add(processoAtual);
                so.gp.rodando = null;
                int endLogico = hw.cpu.reg[9];
                so.filaPedidosConsole.add(new IORequest(processoAtual, endLogico, tipoChamada));
                so.semaConsole.release(); // Libera a ThreadConsole para processar o pedido
                so.semaEscalonador.release(); // Libera o Escalonador para trocar o processo
            } else {
                // Chamada de sistema desconhecida (exceto STOP, que é tratado separadamente)
                System.out.println("    > TRAP: Chamada de sistema inválida: " + tipoChamada);
                // Tratar como interrupção fatal
                so.ih.handle(Interrupts.intInstrucaoInvalida);
            }       
        }
    }

    // ------------------ U T I L I T A R I O S D O S I S T E M A
    // -----------------------------------------
    // ------------------ load é invocado a partir de requisição do usuário

    // carga na memória
    public class Utilities {
        private HW hw;
        private GerenteMemoria gm;

        public Utilities(HW _hw, GerenteMemoria _gm) {
            hw = _hw;
            gm = _gm;
        }

        private void loadProgram(Word[] p) {
            // 1. Alocar memória para o programa
            int[] tabelaPaginas = gm.aloca(p.length);

            // 2. Verificar falha na alocação
            if (tabelaPaginas == null) {
                throw new RuntimeException("Memória insuficiente para carregar o programa.");
            }

            // 3. Configurar a 'MMU' (CPU) com a tabela de páginas
            hw.cpu.setTabelaPaginas(tabelaPaginas);

            // 4. Realizar a Cópia Paginada (Scatter-Loading)
            for (int i = 0; i < tabelaPaginas.length; i++) { // Para cada página lógica 'i'
                int frameIndex = tabelaPaginas[i];
                int endFisicoBase = frameIndex * hw.tamPg;
                int endLogicoBase = i * hw.tamPg;

                for (int j = 0; j < hw.tamPg; j++) { // Para cada palavra 'j' na página
                    int endLogico = endLogicoBase + j;
                    int endFisico = endFisicoBase + j;

                    if (endLogico < p.length) { // Evita copiar além do fim do programa
                        hw.mem.pos[endFisico] = p[endLogico];
                    }
                }
            }
        }

        // dump da memória
        public void dump(Word w) { // funcoes de DUMP nao existem em hardware - colocadas aqui para facilidade
            System.out.print("[ ");
            System.out.print(w.opc);
            System.out.print(", ");
            System.out.print(w.ra);
            System.out.print(", ");
            System.out.print(w.rb);
            System.out.print(", ");
            System.out.print(w.p);
            System.out.println("  ] ");
        }

        public void dump(int ini, int fim) {
            Word[] m = hw.mem.pos; // m[] é o array de posições memória do hw
            for (int i = ini; i < fim; i++) {
                System.out.print(i);
                System.out.print(":  ");
                dump(m[i]);
            }
        }

        private void loadAndExec(Word[] p) {
            loadProgram(p); // carga do programa na memoria
            System.out.println("---------------------------------- programa carregado na memoria");
            dump(0, p.length); // dump da memoria nestas posicoes
            hw.cpu.setContext(0, new int[10], null); // seta pc para endereço 0 - ponto de entrada dos programas
            System.out.println("---------------------------------- inicia execucao ");
            hw.cpu.run_one_instruction(); // cpu roda programa ate parar
            System.out.println("---------------------------------- memoria após execucao ");
            dump(0, p.length); // dump da memoria com resultado
        }
    }

    public class SO {
        public InterruptHandling ih;
        public SysCallHandling sc;
        public Utilities utils;
        public GerenteProcessos gp;
        public HW hw;

        // Estruturas de sincronização
        public Semaphore semaCPU = new Semaphore(0);
        public Semaphore semaEscalonador = new Semaphore(0);
        public Semaphore semaConsole = new Semaphore(0);
        public LinkedBlockingQueue<IORequest> filaPedidosConsole = new LinkedBlockingQueue<>();

        // Threads do sistema
        public ThreadEscalonador escalonador;
        public ThreadCPU cpuThread;
        public ThreadConsole console;

        public SO(HW hw, GerenteMemoria gm, int tamPg) {
            this.hw = hw;
            utils = new Utilities(hw, gm);
            gp = new GerenteProcessos(hw, gm, tamPg, utils, this);
            ih = new InterruptHandling(this);
            sc = new SysCallHandling(hw, this);
            hw.cpu.setAddressOfHandlers(ih, sc);
            
            // Instanciar as threads
            escalonador = new ThreadEscalonador();
            cpuThread = new ThreadCPU();
            console = new ThreadConsole();
        }
    }
    // -------------------------------------------------------------------------------------------------------
    // ------------------- S I S T E M A
    // --------------------------------------------------------------------

    public HW hw;
    public SO so;
    public Programs progs;
    public GerenteMemoria gerenteMem;
    private int tamPagina;

    public Sistema(int tamMem, int _tamPagina) {
        this.tamPagina = _tamPagina;
        hw = new HW(tamMem, _tamPagina);           // memoria do HW tem tamMem palavras
        gerenteMem = new GerenteMemoria(tamMem, _tamPagina);
        so = new SO(hw, gerenteMem, _tamPagina);
        hw.cpu.setUtilities(so.utils); // permite cpu fazer dump de memoria ao avancar
        progs = new Programs();
    }

    // CLI interativa
    public void runCLI() {
        System.out.println("Sistema Operacional iniciado. Digite 'help' para comandos.");
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.print("> ");
            String line = scanner.nextLine().trim();
            
            if (line.isEmpty()) {
                continue;
            }
            
            String[] args = line.split("\\s+");
            String cmd = args[0].toLowerCase();
            
            try {
                switch (cmd) {
                    case "new":
                        if (args.length < 2) {
                            throw new ArrayIndexOutOfBoundsException();
                        }
                        Program p = progs.retrieveProgram(args[1]);
                        if (p == null) {
                            System.out.println("Erro: Programa '" + args[1] + "' não encontrado.");
                        } else {
                            boolean sucesso = so.gp.criaProcesso(p);
                            if (!sucesso) {
                                System.out.println("Erro: Falha ao criar processo (provavelmente sem memória).");
                            }
                        }
                        break;
                        
                    case "rm":
                        if (args.length < 2) {
                            throw new ArrayIndexOutOfBoundsException();
                        }
                        int id = Integer.parseInt(args[1]);
                        so.gp.desalocaProcesso(id);
                        break;
                        
                    case "ps":
                        so.gp.ps();
                        break;
                        
                    case "dump":
                        if (args.length < 2) {
                            throw new ArrayIndexOutOfBoundsException();
                        }
                        int dumpId = Integer.parseInt(args[1]);
                        so.gp.dump(dumpId);
                        break;
                        
                    case "dumpm":
                        if (args.length < 3) {
                            throw new ArrayIndexOutOfBoundsException();
                        }
                        int inicio = Integer.parseInt(args[1]);
                        int fim = Integer.parseInt(args[2]);
                        so.utils.dump(inicio, fim);
                        break;
                        
                    case "traceon":
                        hw.cpu.setDebug(true);
                        System.out.println("CPU trace ligado.");
                        break;
                        
                    case "traceoff":
                        hw.cpu.setDebug(false);
                        System.out.println("CPU trace desligado.");
                        break;
                        
                    case "help":
                        System.out.println("=== COMANDOS DISPONÍVEIS ===");
                        System.out.println("new [prog]    - Cria novo processo com programa 'prog'");
                        System.out.println("rm [id]       - Remove processo com ID 'id'");
                        System.out.println("ps            - Lista todos os processos");
                        System.out.println("dump [id]     - Mostra detalhes do processo 'id'");
                        System.out.println("dumpm [ini] [fim] - Dump da memória física de 'ini' a 'fim'");
                        System.out.println("traceon       - Liga trace da CPU");
                        System.out.println("traceoff      - Desliga trace da CPU");
                        System.out.println("exit          - Encerra o sistema");
                        System.out.println("help          - Mostra esta ajuda");
                        break;
                        
                    case "exit":
                        System.out.println("Encerrando sistema...");
                        scanner.close();
                        System.exit(0);
                        break;
                        
                    default:
                        System.out.println("Erro: Comando '" + cmd + "' não reconhecido. Digite 'help'.");
                        break;
                }
            } catch (NumberFormatException e) {
                System.out.println("Erro: Argumento inválido. Esperava um número (ID ou endereço).");
            } catch (ArrayIndexOutOfBoundsException e) {
                System.out.println("Erro: Faltam argumentos para o comando '" + cmd + "'. Digite 'help'.");
            } catch (Exception e) {
                System.out.println("Erro inesperado: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    // ------------------- S I S T E M A - fim
    // --------------------------------------------------------------
    // -------------------------------------------------------------------------------------------------------

    // -------------------------------------------------------------------------------------------------------
    // ------------------- instancia e testa sistema
    public static void main(String args[]) {
        Sistema s = new Sistema(1024, 16);  // 1024 palavras, página de 16 palavras
        
        // Inicia threads do sistema
        Thread threadEscalonador = new Thread(s.so.escalonador);
        Thread threadCPU = new Thread(s.so.cpuThread);
        Thread threadConsole = new Thread(s.so.console);
        
        threadEscalonador.start();
        threadCPU.start();
        threadConsole.start();
        
        // Executa CLI no thread principal
        s.runCLI();
    }

    // -------------------------------------------------------------------------------------------------------
    // -------------------------------------------------------------------------------------------------------
    // -------------------------------------------------------------------------------------------------------
    // --------------- P R O G R A M A S - não fazem parte do sistema
    // esta classe representa programas armazenados (como se estivessem em disco)
    // que podem ser carregados para a memória (load faz isto)

    public class Program {
        public String name;
        public Word[] image;

        public Program(String n, Word[] i) {
            name = n;
            image = i;
        }
    }

    public class Programs {

        public Program retrieveProgram(String pname) {
            for (Program p : progs) {
                if (p != null && p.name.equals(pname))
                    return p;
            }
            return null;
        }

        public Program[] progs = {
                new Program("fatorial",
                        new Word[] {
                                // este fatorial so aceita valores positivos. nao pode ser zero
                                // linha coment
                                new Word(Opcode.LDI, 0, -1, 7), // 0 r0 é valor a calcular fatorial
                                new Word(Opcode.LDI, 1, -1, 1), // 1 r1 é 1 para multiplicar (por r0)
                                new Word(Opcode.LDI, 6, -1, 1), // 2 r6 é 1 o decremento
                                new Word(Opcode.LDI, 7, -1, 8), // 3 r7 tem posicao 8 para fim do programa
                                new Word(Opcode.JMPIE, 7, 0, 0), // 4 se r0=0 pula para r7(=8)
                                new Word(Opcode.MULT, 1, 0, -1), // 5 r1 = r1 * r0 (r1 acumula o produto por cada termo)
                                new Word(Opcode.SUB, 0, 6, -1), // 6 r0 = r0 - r6 (r6=1) decrementa r0 para proximo
                                                                // termo
                                new Word(Opcode.JMP, -1, -1, 4), // 7 vai p posicao 4
                                new Word(Opcode.STD, 1, -1, 10), // 8 coloca valor de r1 na posição 10
                                new Word(Opcode.STOP, -1, -1, -1), // 9 stop
                                new Word(Opcode.DATA, -1, -1, -1) // 10 ao final o valor está na posição 10 da memória
                        }),

                new Program("fatorialV2",
                        new Word[] {
                                new Word(Opcode.LDI, 0, -1, 5), // numero para colocar na memoria, ou pode ser lido
                                new Word(Opcode.STD, 0, -1, 19),
                                new Word(Opcode.LDD, 0, -1, 19),
                                new Word(Opcode.LDI, 1, -1, -1),
                                new Word(Opcode.LDI, 2, -1, 13), // SALVAR POS STOP
                                new Word(Opcode.JMPIL, 2, 0, -1), // caso negativo pula pro STD
                                new Word(Opcode.LDI, 1, -1, 1),
                                new Word(Opcode.LDI, 6, -1, 1),
                                new Word(Opcode.LDI, 7, -1, 13),
                                new Word(Opcode.JMPIE, 7, 0, 0), // POS 9 pula para STD (Stop-1)
                                new Word(Opcode.MULT, 1, 0, -1),
                                new Word(Opcode.SUB, 0, 6, -1),
                                new Word(Opcode.JMP, -1, -1, 9), // pula para o JMPIE
                                new Word(Opcode.STD, 1, -1, 18),
                                new Word(Opcode.LDI, 8, -1, 2), // escrita
                                new Word(Opcode.LDI, 9, -1, 18), // endereco com valor a escrever
                                new Word(Opcode.SYSCALL, -1, -1, -1),
                                new Word(Opcode.STOP, -1, -1, -1), // POS 17
                                new Word(Opcode.DATA, -1, -1, -1), // POS 18
                                new Word(Opcode.DATA, -1, -1, -1) } // POS 19
                ),

                new Program("progMinimo",
                        new Word[] {
                                new Word(Opcode.LDI, 0, -1, 999),
                                new Word(Opcode.STD, 0, -1, 8),
                                new Word(Opcode.STD, 0, -1, 9),
                                new Word(Opcode.STD, 0, -1, 10),
                                new Word(Opcode.STD, 0, -1, 11),
                                new Word(Opcode.STD, 0, -1, 12),
                                new Word(Opcode.STOP, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1), // 7
                                new Word(Opcode.DATA, -1, -1, -1), // 8
                                new Word(Opcode.DATA, -1, -1, -1), // 9
                                new Word(Opcode.DATA, -1, -1, -1), // 10
                                new Word(Opcode.DATA, -1, -1, -1), // 11
                                new Word(Opcode.DATA, -1, -1, -1), // 12
                                new Word(Opcode.DATA, -1, -1, -1) // 13
                        }),

                new Program("fibonacci10",
                        new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
                                new Word(Opcode.LDI, 1, -1, 0),
                                new Word(Opcode.STD, 1, -1, 20),
                                new Word(Opcode.LDI, 2, -1, 1),
                                new Word(Opcode.STD, 2, -1, 21),
                                new Word(Opcode.LDI, 0, -1, 22),
                                new Word(Opcode.LDI, 6, -1, 6),
                                new Word(Opcode.LDI, 7, -1, 31),
                                new Word(Opcode.LDI, 3, -1, 0),
                                new Word(Opcode.ADD, 3, 1, -1),
                                new Word(Opcode.LDI, 1, -1, 0),
                                new Word(Opcode.ADD, 1, 2, -1),
                                new Word(Opcode.ADD, 2, 3, -1),
                                new Word(Opcode.STX, 0, 2, -1),
                                new Word(Opcode.ADDI, 0, -1, 1),
                                new Word(Opcode.SUB, 7, 0, -1),
                                new Word(Opcode.JMPIG, 6, 7, -1),
                                new Word(Opcode.STOP, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1), // POS 20
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1) // ate aqui - serie de fibonacci ficara armazenada
                        }),

                new Program("fibonacci10v2",
                        new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
                                new Word(Opcode.LDI, 1, -1, 0),
                                new Word(Opcode.STD, 1, -1, 20),
                                new Word(Opcode.LDI, 2, -1, 1),
                                new Word(Opcode.STD, 2, -1, 21),
                                new Word(Opcode.LDI, 0, -1, 22),
                                new Word(Opcode.LDI, 6, -1, 6),
                                new Word(Opcode.LDI, 7, -1, 31),
                                new Word(Opcode.MOVE, 3, 1, -1),
                                new Word(Opcode.MOVE, 1, 2, -1),
                                new Word(Opcode.ADD, 2, 3, -1),
                                new Word(Opcode.STX, 0, 2, -1),
                                new Word(Opcode.ADDI, 0, -1, 1),
                                new Word(Opcode.SUB, 7, 0, -1),
                                new Word(Opcode.JMPIG, 6, 7, -1),
                                new Word(Opcode.STOP, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1), // POS 20
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1) // ate aqui - serie de fibonacci ficara armazenada
                        }),
                new Program("fibonacciREAD",
                        new Word[] {
                                // mesmo que prog exemplo, so que usa r0 no lugar de r8
                                new Word(Opcode.LDI, 8, -1, 1), // leitura
                                new Word(Opcode.LDI, 9, -1, 55), // endereco a guardar o tamanho da serie de fib a gerar
                                                                  // - pode ser de 1 a 20
                                new Word(Opcode.SYSCALL, -1, -1, -1),
                                new Word(Opcode.LDD, 7, -1, 55),
                                new Word(Opcode.LDI, 3, -1, 0),
                                new Word(Opcode.ADD, 3, 7, -1),
                                new Word(Opcode.LDI, 4, -1, 36), // posicao para qual ira pular (stop) *
                                new Word(Opcode.LDI, 1, -1, -1), // caso negativo
                                new Word(Opcode.STD, 1, -1, 41),
                                new Word(Opcode.JMPIL, 4, 7, -1), // pula pra stop caso negativo *
                                new Word(Opcode.JMPIE, 4, 7, -1), // pula pra stop caso 0
                                new Word(Opcode.ADDI, 7, -1, 41), // fibonacci + posição do stop
                                new Word(Opcode.LDI, 1, -1, 0),
                                new Word(Opcode.STD, 1, -1, 41), // 25 posicao de memoria onde inicia a serie de
                                                                  // fibonacci gerada
                                new Word(Opcode.SUBI, 3, -1, 1), // se 1 pula pro stop
                                new Word(Opcode.JMPIE, 4, 3, -1),
                                new Word(Opcode.ADDI, 3, -1, 1),
                                new Word(Opcode.LDI, 2, -1, 1),
                                new Word(Opcode.STD, 2, -1, 42),
                                new Word(Opcode.SUBI, 3, -1, 2), // se 2 pula pro stop
                                new Word(Opcode.JMPIE, 4, 3, -1),
                                new Word(Opcode.LDI, 0, -1, 43),
                                new Word(Opcode.LDI, 6, -1, 25), // salva posição de retorno do loop
                                new Word(Opcode.LDI, 5, -1, 0), // salva tamanho
                                new Word(Opcode.ADD, 5, 7, -1),
                                new Word(Opcode.LDI, 7, -1, 0), // zera (inicio do loop)
                                new Word(Opcode.ADD, 7, 5, -1), // recarrega tamanho
                                new Word(Opcode.LDI, 3, -1, 0),
                                new Word(Opcode.ADD, 3, 1, -1),
                                new Word(Opcode.LDI, 1, -1, 0),
                                new Word(Opcode.ADD, 1, 2, -1),
                                new Word(Opcode.ADD, 2, 3, -1),
                                new Word(Opcode.STX, 0, 2, -1),
                                new Word(Opcode.ADDI, 0, -1, 1),
                                new Word(Opcode.SUB, 7, 0, -1),
                                new Word(Opcode.JMPIG, 6, 7, -1), // volta para o inicio do loop
                                new Word(Opcode.STOP, -1, -1, -1), // POS 36
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1), // POS 41
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1)
                        }),
                new Program("PB",
                        new Word[] {
                                // dado um inteiro em alguma posição de memória,
                                // se for negativo armazena -1 na saída; se for positivo responde o fatorial do
                                // número na saída
                                new Word(Opcode.LDI, 0, -1, 7), // numero para colocar na memoria
                                new Word(Opcode.STD, 0, -1, 50),
                                new Word(Opcode.LDD, 0, -1, 50),
                                new Word(Opcode.LDI, 1, -1, -1),
                                new Word(Opcode.LDI, 2, -1, 13), // SALVAR POS STOP
                                new Word(Opcode.JMPIL, 2, 0, -1), // caso negativo pula pro STD
                                new Word(Opcode.LDI, 1, -1, 1),
                                new Word(Opcode.LDI, 6, -1, 1),
                                new Word(Opcode.LDI, 7, -1, 13),
                                new Word(Opcode.JMPIE, 7, 0, 0), // POS 9 pula pra STD (Stop-1)
                                new Word(Opcode.MULT, 1, 0, -1),
                                new Word(Opcode.SUB, 0, 6, -1),
                                new Word(Opcode.JMP, -1, -1, 9), // pula para o JMPIE
                                new Word(Opcode.STD, 1, -1, 15),
                                new Word(Opcode.STOP, -1, -1, -1), // POS 14
                                new Word(Opcode.DATA, -1, -1, -1) // POS 15
                        }),
                new Program("PC",
                        new Word[] {
                                // Para um N definido (10 por exemplo)
                                // o programa ordena um vetor de N números em alguma posição de memória;
                                // ordena usando bubble sort
                                // loop ate que não swap nada
                                // passando pelos N valores
                                // faz swap de vizinhos se da esquerda maior que da direita
                                new Word(Opcode.LDI, 7, -1, 5), // TAMANHO DO BUBBLE SORT (N)
                                new Word(Opcode.LDI, 6, -1, 5), // aux N
                                new Word(Opcode.LDI, 5, -1, 46), // LOCAL DA MEMORIA
                                new Word(Opcode.LDI, 4, -1, 47), // aux local memoria
                                new Word(Opcode.LDI, 0, -1, 4), // colocando valores na memoria
                                new Word(Opcode.STD, 0, -1, 46),
                                new Word(Opcode.LDI, 0, -1, 3),
                                new Word(Opcode.STD, 0, -1, 47),
                                new Word(Opcode.LDI, 0, -1, 5),
                                new Word(Opcode.STD, 0, -1, 48),
                                new Word(Opcode.LDI, 0, -1, 1),
                                new Word(Opcode.STD, 0, -1, 49),
                                new Word(Opcode.LDI, 0, -1, 2),
                                new Word(Opcode.STD, 0, -1, 50), // colocando valores na memoria até aqui - POS 13
                                new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 1
                                new Word(Opcode.STD, 3, -1, 99),
                                new Word(Opcode.LDI, 3, -1, 22), // Posicao para pulo CHAVE 2
                                new Word(Opcode.STD, 3, -1, 98),
                                new Word(Opcode.LDI, 3, -1, 38), // Posicao para pulo CHAVE 3
                                new Word(Opcode.STD, 3, -1, 97),
                                new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 4 (não usada)
                                new Word(Opcode.STD, 3, -1, 96),
                                new Word(Opcode.LDI, 6, -1, 0), // r6 = r7 - 1 POS 22
                                new Word(Opcode.ADD, 6, 7, -1),
                                new Word(Opcode.SUBI, 6, -1, 1), // ate aqui
                                new Word(Opcode.JMPIEM, -1, 6, 97), // CHAVE 3 para pular quando r7 for 1 e r6 0 para
                                                                     // interomper o loop de vez do programa
                                new Word(Opcode.LDX, 0, 5, -1), // r0 e ra pegando valores das posições da memoria POS
                                                                 // 26
                                new Word(Opcode.LDX, 1, 4, -1),
                                new Word(Opcode.LDI, 2, -1, 0),
                                new Word(Opcode.ADD, 2, 0, -1),
                                new Word(Opcode.SUB, 2, 1, -1),
                                new Word(Opcode.ADDI, 4, -1, 1),
                                new Word(Opcode.SUBI, 6, -1, 1),
                                new Word(Opcode.JMPILM, -1, 2, 99), // LOOP chave 1 caso neg procura prox
                                new Word(Opcode.STX, 5, 1, -1),
                                new Word(Opcode.SUBI, 4, -1, 1),
                                new Word(Opcode.STX, 4, 0, -1),
                                new Word(Opcode.ADDI, 4, -1, 1),
                                new Word(Opcode.JMPIGM, -1, 6, 99), // LOOP chave 1 POS 38
                                new Word(Opcode.ADDI, 5, -1, 1),
                                new Word(Opcode.SUBI, 7, -1, 1),
                                new Word(Opcode.LDI, 4, -1, 0), // r4 = r5 + 1 POS 41
                                new Word(Opcode.ADD, 4, 5, -1),
                                new Word(Opcode.ADDI, 4, -1, 1), // ate aqui
                                new Word(Opcode.JMPIGM, -1, 7, 98), // LOOP chave 2
                                new Word(Opcode.STOP, -1, -1, -1), // POS 45
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1)
                        })
        };
    }
}