package pl.dch.payment.simulation

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.dch.payment.payments.AuthorizationDecision

class ScenarioSimulatorTest {

    private val simulator = ScenarioSimulator(SimulationProperties(enabled = true, slowDelay = Duration.ofMillis(10)))

    @Test
    fun `missing header means normal processing`() {
        assertThat(simulator.resolve(null)).isEqualTo(PaymentScenario.SUCCESS)
        assertThat(simulator.resolve(" ")).isEqualTo(PaymentScenario.SUCCESS)
    }

    @Test
    fun `header value is case insensitive and unknown values are rejected`() {
        assertThat(simulator.resolve("declined")).isEqualTo(PaymentScenario.DECLINED)
        assertThatThrownBy { simulator.resolve("RANDOM") }.isInstanceOf(InvalidScenarioException::class.java)
    }

    @Test
    fun `disabled simulation ignores the header`() {
        val disabled = ScenarioSimulator(SimulationProperties(enabled = false, slowDelay = Duration.ZERO))

        assertThat(disabled.resolve("SERVER_ERROR")).isEqualTo(PaymentScenario.SUCCESS)
    }

    @Test
    fun `only DECLINED changes the authorization decision`() {
        assertThat(simulator.authorizationDecision(PaymentScenario.DECLINED)).isEqualTo(AuthorizationDecision.DECLINED)
        PaymentScenario.entries.filter { it != PaymentScenario.DECLINED }.forEach {
            assertThat(simulator.authorizationDecision(it)).isEqualTo(AuthorizationDecision.APPROVED)
        }
    }

    @Test
    fun `SERVER_ERROR fails every time, SERVER_ERROR_ONCE only the first time per key`() {
        repeat(2) {
            assertThatThrownBy { simulator.beforeProcessing(PaymentScenario.SERVER_ERROR, "a") }
                .isInstanceOf(SimulatedServerErrorException::class.java)
        }

        assertThatThrownBy { simulator.beforeProcessing(PaymentScenario.SERVER_ERROR_ONCE, "b") }
            .isInstanceOf(SimulatedServerErrorException::class.java)
        assertThatCode { simulator.beforeProcessing(PaymentScenario.SERVER_ERROR_ONCE, "b") }.doesNotThrowAnyException()
        assertThatThrownBy { simulator.beforeProcessing(PaymentScenario.SERVER_ERROR_ONCE, "c") }
            .isInstanceOf(SimulatedServerErrorException::class.java)
    }
}
