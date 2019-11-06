package org.qora.test;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.qora.account.Account;
import org.qora.block.Block;
import org.qora.data.block.BlockSummaryData;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.RepositoryManager;
import org.qora.test.common.Common;
import org.qora.test.common.TestAccount;
import org.qora.transform.Transformer;
import org.qora.transform.block.BlockTransformer;
import org.junit.Before;
import org.junit.Test;

public class ChainWeightTests extends Common {

	private static final Random RANDOM = new Random();

	@Before
	public void beforeTest() throws DataException {
		Common.useSettings("test-settings-v2-minting.json");
	}

	private static BlockSummaryData genBlockSummary(Repository repository, int height) {
		TestAccount testAccount = Common.getRandomTestAccount(repository, true);
		byte[] minterPublicKey = testAccount.getPublicKey();

		byte[] signature = new byte[BlockTransformer.BLOCK_SIGNATURE_LENGTH];
		RANDOM.nextBytes(signature);

		int onlineAccountsCount = RANDOM.nextInt(1000);

		return new BlockSummaryData(height, signature, minterPublicKey, onlineAccountsCount);
	}

	private static List<BlockSummaryData> genBlockSummaries(Repository repository, int count, BlockSummaryData commonBlockSummary) {
		List<BlockSummaryData> blockSummaries = new ArrayList<>();
		blockSummaries.add(commonBlockSummary);

		final int commonBlockHeight = commonBlockSummary.getHeight();

		for (int i = 1; i <= count; ++i)
			blockSummaries.add(genBlockSummary(repository, commonBlockHeight + i));

		return blockSummaries;
	}

	// Check that more online accounts beats a better key
	@Test
	public void testMoreAccountsBlock() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			final int parentHeight = 1;
			final byte[] parentMinterKey = new byte[Transformer.PUBLIC_KEY_LENGTH];

			int betterAccountsCount = 100;
			int worseAccountsCount = 20;

			TestAccount betterAccount = Common.getTestAccount(repository, "bob-reward-share");
			byte[] betterKey = betterAccount.getPublicKey();
			int betterMinterLevel = Account.getRewardShareEffectiveMintingLevel(repository, betterKey);

			TestAccount worseAccount = Common.getTestAccount(repository, "dilbert-reward-share");
			byte[] worseKey = worseAccount.getPublicKey();
			int worseMinterLevel = Account.getRewardShareEffectiveMintingLevel(repository, worseKey);

			// This is to check that the hard-coded keys ARE actually better/worse as expected, before moving on testing more online accounts
			BigInteger betterKeyDistance = Block.calcKeyDistance(parentHeight, parentMinterKey, betterKey, betterMinterLevel);
			BigInteger worseKeyDistance = Block.calcKeyDistance(parentHeight, parentMinterKey, worseKey, worseMinterLevel);
			assertEquals("hard-coded keys are wrong", 1, betterKeyDistance.compareTo(worseKeyDistance));

			BlockSummaryData betterBlockSummary = new BlockSummaryData(parentHeight + 1, null, worseKey, betterAccountsCount);
			BlockSummaryData worseBlockSummary = new BlockSummaryData(parentHeight + 1, null, betterKey, worseAccountsCount);

			populateBlockSummaryMinterLevel(repository, betterBlockSummary);
			populateBlockSummaryMinterLevel(repository, worseBlockSummary);

			BigInteger betterBlockWeight = Block.calcBlockWeight(parentHeight, parentMinterKey, betterBlockSummary);
			BigInteger worseBlockWeight = Block.calcBlockWeight(parentHeight, parentMinterKey, worseBlockSummary);

			assertEquals("block weights are wrong", 1, betterBlockWeight.compareTo(worseBlockWeight));
		}
	}

	// Check that a longer chain beats a shorter chain
	@Test
	public void testLongerChain() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			final int commonBlockHeight = 1;
			BlockSummaryData commonBlockSummary = genBlockSummary(repository, commonBlockHeight);
			byte[] commonBlockGeneratorKey = commonBlockSummary.getMinterPublicKey();

			List<BlockSummaryData> shorterChain = genBlockSummaries(repository, 3, commonBlockSummary);
			List<BlockSummaryData> longerChain = genBlockSummaries(repository, shorterChain.size() + 1, commonBlockSummary);

			populateBlockSummariesMinterLevels(repository, shorterChain);
			populateBlockSummariesMinterLevels(repository, longerChain);

			BigInteger shorterChainWeight = Block.calcChainWeight(commonBlockHeight, commonBlockGeneratorKey, shorterChain);
			BigInteger longerChainWeight = Block.calcChainWeight(commonBlockHeight, commonBlockGeneratorKey, longerChain);

			assertEquals("longer chain should have greater weight", 1, longerChainWeight.compareTo(shorterChainWeight));
		}
	}

	// Check that a higher level account wins more blocks
	@Test
	public void testMinterLevel() throws DataException {
		testMinterLevels("chloe-reward-share", "bob-reward-share");
	}

	private void testMinterLevels(String betterMinterName, String worseMinterName) throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount betterAccount = Common.getTestAccount(repository, betterMinterName);
			byte[] betterKey = betterAccount.getPublicKey();
			int betterMinterLevel = Account.getRewardShareEffectiveMintingLevel(repository, betterKey);

			TestAccount worseAccount = Common.getTestAccount(repository, worseMinterName);
			byte[] worseKey = worseAccount.getPublicKey();
			int worseMinterLevel = Account.getRewardShareEffectiveMintingLevel(repository, worseKey);

			// Check hard-coded accounts have expected better/worse levels
			assertTrue("hard-coded accounts have wrong relative minting levels", betterMinterLevel > worseMinterLevel);

			Random random = new Random();
			final int onlineAccountsCount = 100;
			int betterAccountWins = 0;
			int worseAccountWins = 0;
			byte[] parentSignature = new byte[64];
			random.nextBytes(parentSignature);

			for (int parentHeight = 1; parentHeight < 1000; ++parentHeight) {
				byte[] blockSignature = new byte[64];
				random.nextBytes(blockSignature);

				BlockSummaryData betterBlockSummary = new BlockSummaryData(parentHeight + 1, blockSignature, worseKey, onlineAccountsCount);
				BlockSummaryData worseBlockSummary = new BlockSummaryData(parentHeight + 1, blockSignature, betterKey, onlineAccountsCount);

				populateBlockSummaryMinterLevel(repository, betterBlockSummary);
				populateBlockSummaryMinterLevel(repository, worseBlockSummary);

				BigInteger betterBlockWeight = Block.calcBlockWeight(parentHeight, parentSignature, betterBlockSummary);
				BigInteger worseBlockWeight = Block.calcBlockWeight(parentHeight, parentSignature, worseBlockSummary);

				if (betterBlockWeight.compareTo(worseBlockWeight) >= 0)
					++betterAccountWins;
				else
					++worseAccountWins;

				parentSignature = blockSignature;
			}

			assertTrue("Account with better minting level didn't win more blocks", betterAccountWins > worseAccountWins);
		}
	}

	// Check that a higher level account wins more blocks
	@Test
	public void testFounderMinterLevel() throws DataException {
		testMinterLevels("alice-reward-share", "dilbert-reward-share");
	}

	private void populateBlockSummariesMinterLevels(Repository repository, List<BlockSummaryData> blockSummaries) throws DataException {
		for (int i = 0; i < blockSummaries.size(); ++i) {
			BlockSummaryData blockSummary = blockSummaries.get(i);

			populateBlockSummaryMinterLevel(repository, blockSummary);
		}
	}

	private void populateBlockSummaryMinterLevel(Repository repository, BlockSummaryData blockSummary) throws DataException {
		int minterLevel = Account.getRewardShareEffectiveMintingLevel(repository, blockSummary.getMinterPublicKey());
		assertNotSame("effective minter level should not be zero", 0, minterLevel);

		blockSummary.setMinterLevel(minterLevel);
	}

}
